package org.wwz.ai.application.agent.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.printer.Printer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 将用户指导写入 Session Inbox：活跃 run 下一步取走；已收尾则交给统一唤醒链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunInjectApplicationService {

    private static final int MAX_TEXT_CHARS = 8000;
    private static final int MAX_CLIENT_MESSAGE_ID_CHARS = 160;

    private final ActiveAgentRunRegistry activeAgentRunRegistry;

    @Autowired(required = false)
    private SessionInboxPort sessionInboxPort;

    @Autowired(required = false)
    private BackgroundTaskWakePort backgroundTaskWakePort;

    public Map<String, Object> inject(String sessionId, String requestId, String text) {
        return inject(sessionId, requestId, text, null);
    }

    public Map<String, Object> inject(String sessionId, String requestId,
                                      String text, String clientMessageId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("sessionId", sessionId);

        if (StringUtils.isBlank(requestId)) {
            result.put("accepted", false);
            result.put("message", "requestId 不能为空");
            return result;
        }
        String body = StringUtils.trimToEmpty(text);
        if (StringUtils.isBlank(body)) {
            result.put("accepted", false);
            result.put("message", "text 不能为空");
            return result;
        }
        if (body.length() > MAX_TEXT_CHARS) {
            body = body.substring(0, MAX_TEXT_CHARS);
        }

        Optional<ActiveAgentRunRegistry.ActiveRun> found = activeAgentRunRegistry.find(requestId);
        if (found.isPresent()) {
            ActiveAgentRunRegistry.ActiveRun run = found.get();
            if (StringUtils.isNotBlank(sessionId)
                    && StringUtils.isNotBlank(run.getSessionId())
                    && !sessionId.equals(run.getSessionId())) {
                result.put("accepted", false);
                result.put("message", "sessionId 与 run 不匹配");
                return result;
            }
        }

        String normalizedClientMessageId = StringUtils.trimToNull(clientMessageId);
        if (normalizedClientMessageId != null
                && normalizedClientMessageId.length() > MAX_CLIENT_MESSAGE_ID_CHARS) {
            result.put("accepted", false);
            result.put("message", "clientMessageId 长度不能超过160");
            return result;
        }
        String idempotencyKey = StringUtils.defaultIfBlank(
                normalizedClientMessageId, newClientMessageId());
        if (sessionInboxPort != null) {
            return injectThroughInbox(result, found, sessionId, body, idempotencyKey);
        }

        if (found.isEmpty()) {
            return newRun(result, "父 run 已结束，将作为新消息发送");
        }

        ActiveAgentRunRegistry.ActiveRun run = found.get();
        AgentContext ctx = run.getAgentContext();
        if (ctx != null && ctx.isTurnClosed()) {
            return newRun(result, "父 run 已结束，将作为新消息发送");
        }
        if (run.getCancellation() != null && run.getCancellation().isCancelled()) {
            result.put("accepted", false);
            result.put("message", "run 已在停止中，无法注入");
            return result;
        }

        PendingInjectMessage message = PendingInjectMessage.builder()
                .text(body)
                .source(PendingInjectMessage.SOURCE_USER)
                .createdAtMs(System.currentTimeMillis())
                .build();
        run.getPendingInjects().offer(message);

        if (ctx != null) {
            // bind 后 context 与 ActiveRun 共享队列；再 offer 会重复，上面已 offer 到共享队列
            notifyInjected(ctx, body, idempotencyKey);
        }

        log.info("inject accepted requestId={} sessionId={} chars={}", requestId, run.getSessionId(), body.length());
        result.put("accepted", true);
        result.put("message", "已接受指导，将在下一步生效");
        result.put("queued", true);
        result.put("clientMessageId", idempotencyKey);
        return result;
    }

    private Map<String, Object> injectThroughInbox(Map<String, Object> result,
                                                   Optional<ActiveAgentRunRegistry.ActiveRun> found,
                                                   String sessionId,
                                                   String body,
                                                   String idempotencyKey) {
        SessionInboxItem queued = sessionInboxPort.offer(SessionInboxItem.builder()
                .sessionId(sessionId)
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_PENDING)
                .idempotencyKey(idempotencyKey)
                .payloadJson(SessionInboxPayloads.userMessage(body))
                .sourceRunId(found.map(ActiveAgentRunRegistry.ActiveRun::getRequestId).orElse(null))
                .build());
        result.put("clientMessageId", idempotencyKey);
        if (queued == null) {
            result.put("accepted", false);
            result.put("message", "消息队列暂不可用，请稍后重试");
            return result;
        }
        if (SessionInboxItem.STATE_DELIVERED.equals(queued.getState())) {
            result.put("accepted", true);
            result.put("queued", false);
            result.put("message", "该消息已处理");
            return result;
        }
        boolean autoWakePaused = backgroundTaskWakePort != null
                && backgroundTaskWakePort.isAutoWakePaused(sessionId);
        if (autoWakePaused) {
            sessionInboxPort.holdPendingUserMessages(sessionId);
            result.put("accepted", true);
            result.put("queued", true);
            result.put("held", true);
            result.put("wakeScheduled", false);
            result.put("message", "消息已保留，需你确认后继续");
            return result;
        }
        if (queued.isHeld()) {
            sessionInboxPort.releaseHeldUserMessages(sessionId, idempotencyKey);
        }

        // 入队提交之后必须再判活。入队前拿到的 ActiveRun 可能已经在 offer 期间结束，
        // 若继续信任旧快照，消息会错过本轮收尾复查，也不会安排下一次唤醒。
        boolean wakeScheduled = scheduleInboxWake(sessionId, result);
        Optional<ActiveAgentRunRegistry.ActiveRun> current =
                activeAgentRunRegistry.findBySessionId(sessionId);
        if (current.isEmpty()) {
            result.put("accepted", true);
            result.put("queued", true);
            result.put("message", wakeScheduled
                    ? "已排队，将自动继续处理" : "消息已排队");
            return result;
        }

        ActiveAgentRunRegistry.ActiveRun run = current.get();
        AgentContext ctx = run.getAgentContext();
        if (run.getCancellation() != null && run.getCancellation().isCancelled()) {
            sessionInboxPort.holdPendingUserMessages(sessionId);
            result.put("accepted", true);
            result.put("queued", true);
            result.put("held", true);
            result.put("message", "run 已停止，消息已保留，需你确认后继续");
            return result;
        }
        if (ctx == null || !ctx.isTurnClosed()) {
            if (ctx != null) {
                notifyInjected(ctx, body, idempotencyKey);
            }
            result.put("accepted", true);
            result.put("queued", true);
            result.put("message", "已接受指导，将在下一步生效");
            return result;
        }

        // turnClosed 后 ActiveRun 可能尚未 end：先入队，再由 end 后的 onRunSettled 复查唤醒。
        result.put("accepted", true);
        result.put("queued", true);
        result.put("message", wakeScheduled
                ? "当前回复已收尾，消息已排队并继续处理" : "当前回复已收尾，消息已排队");
        return result;
    }

    private boolean scheduleInboxWake(String sessionId, Map<String, Object> result) {
        if (backgroundTaskWakePort == null) {
            result.put("wakeScheduled", false);
            return false;
        }
        try {
            boolean scheduled = backgroundTaskWakePort.onInboxChanged(sessionId);
            result.put("wakeScheduled", scheduled);
            return scheduled;
        } catch (Exception e) {
            log.warn("schedule inbox wake failed sessionId={}", sessionId, e);
            result.put("wakeScheduled", false);
            return false;
        }
    }

    private static Map<String, Object> newRun(Map<String, Object> result, String message) {
        result.put("accepted", false);
        result.put("mode", "new_run");
        result.put("message", message);
        return result;
    }

    private void notifyInjected(AgentContext ctx, String text, String clientMessageId) {
        Printer printer = ctx.getPrinter();
        if (printer == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            payload.put("source", PendingInjectMessage.SOURCE_USER);
            payload.put("clientMessageId", clientMessageId);
            payload.put("status", "queued");
            printer.send("user_inject", payload);
        } catch (Exception e) {
            log.debug("send user_inject skipped, requestId={}", ctx.getRequestId());
        }
    }

    private static String newClientMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
