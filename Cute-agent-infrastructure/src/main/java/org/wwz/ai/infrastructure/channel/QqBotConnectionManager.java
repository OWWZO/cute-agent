package org.wwz.ai.infrastructure.channel;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.port.ChannelActivationListener;
import org.wwz.ai.domain.agent.channel.port.ChannelConnectionPort;
import org.wwz.ai.domain.agent.channel.port.ChannelInboundPort;
import org.wwz.ai.types.agent.config.ChannelProperties;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotBoundAdapter;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotRestClient;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotTokenService;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotWsClient;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 多租户连接管理：一个绑定一条 WS 连接。
 * <p>启动时并发恢复所有 ACTIVE 绑定；绑定成功后起连接；解绑后停连接。
 * 状态完全在内存（项目本身是单实例架构），用文件锁防同 appId 双连。</p>
 */
@Slf4j
public class QqBotConnectionManager implements ChannelConnectionPort, ChannelActivationListener {

    private final ChannelBindingRepository bindingRepository;
    private final ChannelInboundPort dispatchService;
    private final QqBotTokenService tokenService;
    private final QqBotRestClient restClient;
    private final ChannelProperties properties;
    private final ChannelAppIdLock appIdLock;
    private final OkHttpClient httpClient;

    private final Map<Long, QqBotWsClient> connections = new ConcurrentHashMap<>();
    private final Map<Long, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final ExecutorService connectExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "channel-connect");
        thread.setDaemon(true);
        return thread;
    });

    public QqBotConnectionManager(ChannelBindingRepository bindingRepository,
                                  ChannelInboundPort dispatchService,
                                  QqBotTokenService tokenService,
                                  QqBotRestClient restClient,
                                  ChannelProperties properties,
                                  ChannelAppIdLock appIdLock,
                                  OkHttpClient httpClient) {
        this.bindingRepository = bindingRepository;
        this.dispatchService = dispatchService;
        this.tokenService = tokenService;
        this.restClient = restClient;
        this.properties = properties;
        this.appIdLock = appIdLock;
        this.httpClient = httpClient;
    }

    /**
     * 启动恢复：构造时不能连，否则会在 Spring 容器还没就绪时打网络。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        List<ChannelBinding> active = bindingRepository.listByStatus(ChannelBinding.STATUS_ACTIVE);
        log.info("[channel] loaded {} active bindings", active.size());
        // 并发建连：每个绑定要 1-2 秒，串行会拖慢启动。
        CompletableFuture<?>[] futures = active.stream()
                .map(binding -> CompletableFuture.runAsync(() -> doStart(binding), connectExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("[channel] startup restore finished with errors", error);
            }
        });
    }

    @Override
    public void startConnection(ChannelBinding binding) {
        if (binding == null || binding.getId() == null) {
            return;
        }
        Long bindingId = binding.getId();
        if (connections.containsKey(bindingId)) {
            log.debug("[channel] connection already exists bindingId={}", bindingId);
            return;
        }
        connectExecutor.execute(() -> doStart(binding));
    }

    private void doStart(ChannelBinding binding) {
        Long bindingId = binding.getId();
        // 文件锁：开发时可能同时跑 IDE 和命令行，两个进程用同一 appId 会双投消息。
        if (!appIdLock.tryLock(binding.getAppId())) {
            String error = "appId 已被另一个进程占用";
            log.warn("[channel] {} bindingId={} appId={}", error, bindingId, binding.getAppId());
            markError(bindingId, error);
            return;
        }
        try {
            ChannelAdapter adapter = new QqBotBoundAdapter(restClient, properties, binding);
            adapters.put(bindingId, adapter);

            QqBotWsClient client = new QqBotWsClient(
                    bindingId,
                    binding.getAppId(),
                    binding.getAppSecret(),
                    httpClient,
                    tokenService,
                    properties.getQqbot().getReconnectBackoffSeconds(),
                    properties.getQqbot().getMaxReconnectAttempts(),
                    event -> dispatchService.dispatch(event, binding, adapter),
                    () -> {
                        // 重连次数耗尽：标记 ERROR，等 owner 手动重试。
                        connections.remove(bindingId);
                        adapters.remove(bindingId);
                        appIdLock.unlock(binding.getAppId());
                        markError(bindingId, "连接多次失败，请重试");
                    });
            connections.put(bindingId, client);
            client.start();
            // WS 建连是异步的：这里只标记「已启动」，Ready 之后才算真正连通。
            markActive(bindingId);
            log.info("[channel] qqbot connecting bindingId={} appId={}", bindingId, binding.getAppId());
        } catch (Exception e) {
            log.warn("[channel] start connection failed bindingId={}", bindingId, e);
            connections.remove(bindingId);
            adapters.remove(bindingId);
            appIdLock.unlock(binding.getAppId());
            markError(bindingId, e.getMessage());
        }
    }

    @Override
    public void stopConnection(Long bindingId) {
        if (bindingId == null) {
            return;
        }
        QqBotWsClient client = connections.remove(bindingId);
        adapters.remove(bindingId);
        if (client == null) {
            return;
        }
        try {
            client.stop();
        } catch (Exception e) {
            log.debug("[channel] stop connection failed bindingId={}", bindingId, e);
        }
        bindingRepository.findById(bindingId).ifPresent(
                binding -> appIdLock.unlock(binding.getAppId()));
        log.info("[channel] binding {} → DISABLED", bindingId);
    }

    /**
     * 扫码完成 → 起连接。
     * <p>这是 binding 从 PENDING 走到 ACTIVE 的唯一入口；漏掉它 bot 永远不会上线。</p>
     */
    @Override
    public void onActivated(Long bindingId, String ownerUserId) {
        bindingRepository.findById(bindingId).ifPresentOrElse(
                this::startConnection,
                () -> log.warn("[channel] activated binding not found bindingId={}", bindingId));
    }

    @Override
    public boolean isConnected(Long bindingId) {
        QqBotWsClient client = bindingId == null ? null : connections.get(bindingId);
        return client != null && client.isRunning();
    }

    @Override
    public ChannelAdapter findAdapter(Long bindingId) {
        return bindingId == null ? null : adapters.get(bindingId);
    }

    private void markActive(Long bindingId) {
        bindingRepository.updateStatus(bindingId, ChannelBinding.STATUS_ACTIVE, null, LocalDateTime.now());
    }

    private void markError(Long bindingId, String error) {
        String trimmed = error == null || error.isBlank() ? "连接失败" : error.substring(0, Math.min(error.length(), 500));
        bindingRepository.updateStatus(bindingId, ChannelBinding.STATUS_ERROR, trimmed, null);
    }

    @PreDestroy
    public void shutdown() {
        connectExecutor.shutdownNow();
        connections.keySet().forEach(this::stopConnection);
        try {
            connectExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
