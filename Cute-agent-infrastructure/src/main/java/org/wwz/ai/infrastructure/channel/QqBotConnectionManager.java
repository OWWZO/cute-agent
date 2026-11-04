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

    /** bindingId → appId：建连时缓存，避免绑定行被删后无法归还文件锁。 */
    private final Map<Long, String> bindingAppIds = new ConcurrentHashMap<>();
    private final Map<Long, QqBotWsClient> connections = new ConcurrentHashMap<>();
    private final Map<Long, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<Long, Object> bindingMonitors = new ConcurrentHashMap<>();
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
                .map(binding -> CompletableFuture.runAsync(() -> startConnectionOnCurrentThread(binding), connectExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("[channel] startup restore finished with errors", error);
            }
        });
    }

    @Override
    public void startConnection(ChannelBinding binding) {
        connectExecutor.execute(() -> startConnectionOnCurrentThread(binding));
    }

    private void startConnectionOnCurrentThread(ChannelBinding binding) {
        if (binding == null || binding.getId() == null) {
            return;
        }
        Long bindingId = binding.getId();
        if (connections.containsKey(bindingId)) {
            log.debug("[channel] connection already exists bindingId={}", bindingId);
            return;
        }
        doStart(binding);
    }

    private void doStart(ChannelBinding binding) {
        Long bindingId = binding.getId();
        // start/stop 对同一绑定串行，避免 appId 锁的归还与建连交错。
        synchronized (monitorOf(bindingId)) {
            doStartSerialized(binding);
        }
    }

    /**
     * 每个绑定一把监视器：start/stop 必须串行。
     * <p>否则 stop 归还 appId 锁的同时 start 仍在校验与建连之间，会出现「锁已释放但连接被
     * 拉起」的窗口；反过来 stop 会看到 connections 里还没有 client 而提前返回，把锁留在进程里。</p>
     */
    private Object monitorOf(Long bindingId) {
        return bindingMonitors.computeIfAbsent(bindingId, ignored -> new Object());
    }

    private void doStartSerialized(ChannelBinding binding) {
        Long bindingId = binding.getId();
        // 文件锁：开发时可能同时跑 IDE 和命令行，两个进程用同一 appId 会双投消息。
        if (!appIdLock.tryLock(binding.getAppId())) {
            String error = "appId 已被另一个进程占用";
            log.warn("[channel] {} bindingId={} appId={}", error, bindingId, binding.getAppId());
            markError(bindingId, error);
            return;
        }
        bindingAppIds.put(bindingId, binding.getAppId());
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
                        releaseAppIdLock(bindingId);
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
            releaseAppIdLock(bindingId);
            markError(bindingId, e.getMessage());
        }
    }

    @Override
    public void stopConnection(Long bindingId) {
        if (bindingId == null) {
            return;
        }
        synchronized (monitorOf(bindingId)) {
            QqBotWsClient client = connections.remove(bindingId);
            adapters.remove(bindingId);
            try {
                if (client != null) {
                    client.stop();
                }
            } catch (Exception e) {
                log.debug("[channel] stop connection failed bindingId={}", bindingId, e);
            } finally {
                // client 为空说明这次停的是「没连上或已断开」的绑定；但建连失败、或 stop 与
                // start 交错时，appId 锁仍由本进程持有。锁必须在任何退出路径上归还，否则该
                // appId 永远无法再次建立连接（只能重启进程）。
                releaseAppIdLock(bindingId);
            }
            log.info("[channel] binding {} → DISABLED", bindingId);
        }
    }

    /** 归还绑定占用的 appId 锁；锁未持有时 unlock 是幂等的空操作。 */
    private void releaseAppIdLock(Long bindingId) {
        String appId = bindingAppIds.remove(bindingId);
        if (appId == null) {
            // 兼容：进程重启后未建连就被 stop 的绑定，只能回查库
            try {
                appId = bindingRepository.findById(bindingId)
                        .map(ChannelBinding::getAppId).orElse(null);
            } catch (Exception e) {
                log.warn("[channel] lookup appId failed bindingId={}", bindingId, e);
            }
        }
        if (appId != null) {
            appIdLock.unlock(appId);
        }
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
