package org.wwz.ai.application.agent.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
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
        if (StringUtils.isBlank(sessionId) || !cuteConfig.isBackgroundAutoContinuation()) {
            return;
        }
        String sid = sessionId.trim();
        if (SessionBackgroundTaskHub.hasRunning(sid)) {
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
        if (SessionNotificationHub.hasPending(sid)) {
            scheduleWake(sid);
            return;
        }
        completeStaleProjections(sid, stale);
    }

    private void scheduleWake(String sessionId) {
        if (!SessionNotificationHub.hasPending(sessionId)) {
            return;
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
        } catch (RejectedExecutionException e) {
            log.warn("background wake schedule rejected sessionId={}", sessionId, e);
        }
    }

    private void dispatchIfStillIdle(String sessionId) {
        if (!cuteConfig.isBackgroundAutoContinuation()) {
            return;
        }
        List<AgentStreamProjection> stale = sessionProjectionRegistry.listLive(sessionId);
        if (activeAgentRunRegistry.findBySessionId(sessionId).isPresent()) {
            // 用户抢先开了一轮：让位，通知由这一轮的 drain 取走。
            return;
        }
        if (sessionYieldGate.hasOpenYield(sessionId)) {
            return;
        }
        if (!SessionNotificationHub.hasPending(sessionId)) {
            completeStaleProjections(sessionId, stale);
            return;
        }
        boolean dispatched = autoContinuationService.dispatchWake(sessionId, null);
        if (!dispatched) {
            // 通知仍留在 hub，等下一次用户交互或收口复查；先清掉无人接收的旧投影。
            completeStaleProjections(sessionId, stale);
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
