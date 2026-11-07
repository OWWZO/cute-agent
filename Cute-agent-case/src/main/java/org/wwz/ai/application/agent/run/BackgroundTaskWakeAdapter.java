package org.wwz.ai.application.agent.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 后台任务完成后的三态判活与 500ms 合并唤醒。
 * <p>A：主 run 存活 → 交给下一步边界 drain；B：HITL 挂起 → 交给 resume 轮；
 * C：空闲 → 合并窗口后派发唤醒轮。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundTaskWakeAdapter implements BackgroundTaskWakePort {

    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    private final SessionYieldGate sessionYieldGate;
    private final BackgroundTaskAutoContinuationApplicationService autoContinuationService;
    private final SessionProjectionRegistry sessionProjectionRegistry;
    private final CuteConfig cuteConfig;

    @Autowired(required = false)
    private SessionInboxPort sessionInboxPort;

    @Autowired(required = false)
    private IExecutionLedgerReadRepository executionLedgerReadRepository;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "background-task-wake");
        thread.setDaemon(true);
        return thread;
    });

    /** sessionId → 待触发的合并窗口；按 session debounce。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingWakes = new ConcurrentHashMap<>();

    @Override
    public boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event) {
        if (event == null || StringUtils.isBlank(event.sessionId())) {
            return false;
        }
        if (!cuteConfig.isBackgroundAutoContinuation()) {
            return false;
        }
        if (RuntimeBackgroundTask.STATUS_STOPPED.equals(event.status())) {
            return false;
        }
        String sessionId = event.sessionId().trim();

        Optional<ActiveAgentRunRegistry.ActiveRun> active =
                activeAgentRunRegistry.findBySessionId(sessionId);
        if (active.isPresent()) {
            AgentContext context = active.get().getAgentContext();
            if (context == null) {
                // run 尚未绑定上下文：下一步边界 drain 会取走队列。
                return false;
            }
            // A 态：同一轮 steer，不发 stream_settle（shouldSettleParentStream 会因 turn 未关闭返回 false）。
            // 收尾窗口：主 run 已 finishRun，等 onRunSettled 复查后再唤醒；这里先压住 settle。
            return context.isTurnClosed();
        }
        if (sessionYieldGate.hasOpenYield(sessionId)) {
            // B 态：resume 轮第一步 drain；压住 settle，避免投影在 resume 前被关掉。
            return true;
        }
        // C 态：进入合并窗口，窗口到期后再次判活。
        scheduleWake(sessionId);
        return true;
    }

    @Override
    public void onRunSettled(String sessionId) {
        onRunSettled(sessionId, null);
    }

    @Override
    public void onRunSettled(String sessionId, String requestId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        String sid = sessionId.trim();
        if (sessionInboxPort != null && StringUtils.isNotBlank(requestId)) {
            try {
                sessionInboxPort.releaseClaims(sid, requestId.trim());
            } catch (Exception e) {
                log.warn("session inbox release claims failed sessionId={} requestId={}",
                        sid, requestId, e);
            }
        }
        if (SessionBackgroundTaskHub.hasRunning(sid)
                && !hasPendingKind(sid, SessionInboxItem.KIND_USER_MESSAGE)) {
            // 仍有后台任务在飞：等最后一个完成时再判活。
            return;
        }
        List<AgentStreamProjection> stale = sessionProjectionRegistry.listLive(sid);
        if (activeAgentRunRegistry.findBySessionId(sid).isPresent()) {
            return;
        }
        if (sessionYieldGate.hasOpenYield(sid)) {
            return;
        }
        boolean autoWakePaused = isAutoWakePaused(sid);
        if (autoWakePaused) {
            completeStaleProjections(sid, stale);
            return;
        }
        if (hasWakeablePending(sid, autoWakePaused)) {
            scheduleWake(sid, autoWakePaused);
            return;
        }
        completeStaleProjections(sid, stale);
    }

    @Override
    public boolean onInboxChanged(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        String sid = sessionId.trim();
        if (activeAgentRunRegistry.findBySessionId(sid).isPresent()
                || sessionYieldGate.hasOpenYield(sid)) {
            return false;
        }
        boolean autoWakePaused = isAutoWakePaused(sid);
        if (!hasWakeablePending(sid, autoWakePaused)) {
            return false;
        }
        return scheduleWake(sid, autoWakePaused);
    }

    private boolean scheduleWake(String sessionId) {
        return scheduleWake(sessionId, isAutoWakePaused(sessionId));
    }

    private boolean scheduleWake(String sessionId, boolean autoWakePaused) {
        if (!hasWakeablePending(sessionId, autoWakePaused)) {
            return false;
        }
        long delayMs = Math.max(0L, cuteConfig.getBackgroundAutoContinuationDelayMs());
        ScheduledFuture<?> previous = pendingWakes.remove(sessionId);
        if (previous != null) {
            previous.cancel(false);
        }
        try {
            ScheduledFuture<?> scheduled = scheduler.schedule(
                    () -> {
                        pendingWakes.remove(sessionId);
                        dispatchIfStillIdle(sessionId);
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS);
            pendingWakes.put(sessionId, scheduled);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("background wake schedule rejected sessionId={}", sessionId, e);
            return false;
        }
    }

    private void dispatchIfStillIdle(String sessionId) {
        List<AgentStreamProjection> stale = sessionProjectionRegistry.listLive(sessionId);
        if (activeAgentRunRegistry.findBySessionId(sessionId).isPresent()) {
            // 用户抢先开了一轮：让位，通知由这一轮的 drain 取走。
            return;
        }
        if (sessionYieldGate.hasOpenYield(sessionId)) {
            return;
        }
        boolean autoWakePaused = isAutoWakePaused(sessionId);
        if (!hasWakeablePending(sessionId, autoWakePaused)) {
            completeStaleProjections(sessionId, stale);
            return;
        }
        boolean userPending = hasPendingKind(sessionId, SessionInboxItem.KIND_USER_MESSAGE);
        String triggerSource = userPending ? "user_inbox" : "background_task";
        String displayQuery = userPending && sessionInboxPort != null
                ? sessionInboxPort.peekFirstDeliverableUserText(sessionId)
                : null;
        boolean dispatched = autoContinuationService.dispatchWake(
                sessionId, null, triggerSource, displayQuery,
                requestId -> onRunSettled(sessionId, requestId));
        if (!dispatched) {
            // 通知仍留在 hub，等下一次用户交互或收口复查；先清掉无人接收的旧投影。
            completeStaleProjections(sessionId, stale);
        }
    }

    private boolean hasWakeablePending(String sessionId, boolean autoWakePaused) {
        if (autoWakePaused) {
            return false;
        }
        if (hasPendingKind(sessionId, SessionInboxItem.KIND_USER_MESSAGE)) {
            return true;
        }
        return cuteConfig.isBackgroundAutoContinuation()
                && hasPendingKind(sessionId, SessionInboxItem.KIND_BACKGROUND_NOTIFICATION);
    }

    private boolean hasPendingKind(String sessionId, String kind) {
        if (sessionInboxPort != null) {
            return sessionInboxPort.hasDeliverableKind(sessionId, kind);
        }
        // 兼容未装配 Inbox 的单元测试/旧运行时：只保留后台通知内存队列语义。
        return SessionInboxItem.KIND_BACKGROUND_NOTIFICATION.equals(kind)
                && SessionNotificationHub.hasPending(sessionId);
    }

    @Override
    public boolean isAutoWakePaused(String sessionId) {
        if (sessionInboxPort != null && sessionInboxPort.hasHeldUserMessage(sessionId)) {
            return true;
        }
        if (executionLedgerReadRepository == null) {
            return false;
        }
        try {
            DialogueSession session = executionLedgerReadRepository.querySessionEntity(sessionId);
            return session != null && session.getStatus() != null
                    && session.getStatus() == ExecutionLedgerConstants.STATUS_STOPPED;
        } catch (Exception e) {
            log.debug("session state check failed sessionId={}", sessionId, e);
            return false;
        }
    }

    private void completeStaleProjections(String sessionId, List<AgentStreamProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        for (AgentStreamProjection projection : projections) {
            try {
                projection.complete();
            } catch (Exception e) {
                log.debug("complete stale projection failed sessionId={}", sessionId, e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        pendingWakes.values().forEach(future -> future.cancel(false));
        pendingWakes.clear();
        scheduler.shutdownNow();
    }
}
