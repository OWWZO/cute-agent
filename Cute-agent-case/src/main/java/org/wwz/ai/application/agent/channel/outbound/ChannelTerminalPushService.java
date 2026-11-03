package org.wwz.ai.application.agent.channel.outbound;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.channel.model.ChannelAttachmentRef;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.policy.ChannelAttachmentFormatter;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.port.ChannelConnectionPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 终答直推 IM：主 Agent 每轮终答产生的那一刻直接推送，不订阅投影、不依赖渠道水位。
 * <p>成功与失败共用 {@code (sessionId, requestId)} 幂等集合；推送异步执行，失败只记日志，
 * 不影响 Agent 收口。</p>
 */
@Slf4j
@Service
public class ChannelTerminalPushService {

    private static final int PUSHED_CACHE_MAX = 2048;

    @Resource
    private IExecutionLedgerReadRepository ledgerReadRepository;

    @Resource
    private CuteConfig cuteConfig;

    @Autowired
    private ObjectProvider<ChannelConnectionPort> channelConnectionPortProvider;

    @Autowired
    private ObjectProvider<ChannelArtifactResolver> channelArtifactResolverProvider;

    /** 同一 run 成功/失败只推一次。 */
    private final Map<String, Boolean> pushedRequestIds = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > PUSHED_CACHE_MAX;
        }
    };

    private final ExecutorService pushExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "channel-terminal-push");
        thread.setDaemon(true);
        return thread;
    });

    public void pushSuccess(String sessionId, String requestId, String text) {
        if (StringUtils.isBlank(text)) {
            // 空终答不推，避免向用户发一条只有附件的空消息。
            return;
        }
        push(sessionId, requestId, text, true);
    }

    public void pushFailure(String sessionId, String requestId, String reason) {
        String text = "任务失败：" + StringUtils.defaultIfBlank(reason, "未知原因");
        push(sessionId, requestId, text, false);
    }

    private void push(String sessionId, String requestId, String text, boolean withAttachments) {
        if (cuteConfig == null || !cuteConfig.isChannelTerminalPush()) {
            return;
        }
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(requestId)) {
            return;
        }
        if (!claim(requestIdKey(sessionId, requestId))) {
            return;
        }
        try {
            DialogueSession session = ledgerReadRepository.querySessionEntity(sessionId);
            if (session == null || session.getBindingId() == null) {
                return;
            }
            ChannelChatType chatType = parseChatType(session.getChannelChatType());
            String targetId = StringUtils.trimToNull(session.getChannelTargetId());
            if (chatType == null || targetId == null) {
                log.debug("channel terminal push skipped, target missing sessionId={} requestId={}",
                        sessionId, requestId);
                return;
            }
            ChannelConnectionPort connectionPort = channelConnectionPortProvider.getIfAvailable();
            ChannelAdapter adapter = connectionPort == null
                    ? null
                    : connectionPort.findAdapter(session.getBindingId());
            if (adapter == null) {
                log.debug("channel terminal push skipped, adapter offline bindingId={}",
                        session.getBindingId());
                return;
            }
            String body = withAttachments ? withAttachments(text, requestId) : text;
            ChannelTarget target = new ChannelTarget(
                    adapter.platform(), session.getBindingId(), chatType, targetId, null, Instant.now());
            pushExecutor.execute(() -> {
                try {
                    var result = adapter.pushText(target, body);
                    if (result != null && !result.success()) {
                        log.warn("channel terminal push failed requestId={} error={}",
                                requestId, result.error());
                    }
                } catch (Exception e) {
                    log.warn("channel terminal push failed requestId={}", requestId, e);
                }
            });
        } catch (Exception e) {
            log.warn("channel terminal push skipped sessionId={} requestId={}", sessionId, requestId, e);
        }
    }

    private String withAttachments(String text, String requestId) {
        ChannelArtifactResolver resolver = channelArtifactResolverProvider.getIfAvailable();
        if (resolver == null) {
            return text;
        }
        try {
            List<ChannelAttachmentRef> attachments = resolver.resolve(requestId);
            if (attachments == null || attachments.isEmpty()) {
                return text;
            }
            String block = ChannelAttachmentFormatter.format(attachments);
            return StringUtils.isBlank(block) ? text : text + block;
        } catch (Exception e) {
            log.debug("channel artifact resolve failed requestId={}", requestId, e);
            return text;
        }
    }

    private boolean claim(String key) {
        synchronized (pushedRequestIds) {
            if (pushedRequestIds.containsKey(key)) {
                return false;
            }
            pushedRequestIds.put(key, Boolean.TRUE);
            return true;
        }
    }

    private static String requestIdKey(String sessionId, String requestId) {
        return sessionId.trim() + ":" + requestId.trim();
    }

    private static ChannelChatType parseChatType(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return ChannelChatType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        pushExecutor.shutdownNow();
    }
}
