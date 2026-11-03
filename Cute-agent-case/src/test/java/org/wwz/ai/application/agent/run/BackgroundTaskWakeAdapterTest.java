package org.wwz.ai.application.agent.run;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

/**
 * 三态判活：A（steer）不接管 settle，B（HITL）接管但等 resume，C（空闲）进入合并窗口。
 */
public class BackgroundTaskWakeAdapterTest {

    private BackgroundTaskWakeAdapter adapter;

    @After
    public void tearDown() {
        if (adapter != null) {
            adapter.shutdown();
        }
        SessionNotificationHub.clearAll();
    }

    @Test
    public void flagOffNeverTakesOver() {
        CuteConfig config = config(false);
        adapter = newAdapter(config, new ActiveAgentRunRegistry(), new SessionYieldGate() {
            @Override
            public boolean hasOpenYield(String sessionId) {
                return false;
            }
        });
        Assert.assertFalse(adapter.onBackgroundTaskCompleted(event("s-flag", "completed")));
    }

    @Test
    public void stoppedTaskNeverTakesOver() {
        adapter = newAdapter(config(true), new ActiveAgentRunRegistry(), freeGate());
        Assert.assertFalse(adapter.onBackgroundTaskCompleted(
                event("s-stopped", RuntimeBackgroundTask.STATUS_STOPPED)));
    }

    @Test
    public void liveRunDoesNotTakeOverSettleButClosingTurnDoes() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        AgentContext context = AgentContext.builder()
                .sessionId("s-active")
                .requestId("r-active")
                .build();
        registry.begin("r-active", "s-active", "u1").setAgentContext(context);
        adapter = newAdapter(config(true), registry, freeGate());

        // A 态：主 run 存活，下一步边界 drain；不需要接管 settle。
        Assert.assertFalse(adapter.onBackgroundTaskCompleted(event("s-active", "completed")));

        // 收尾窗口：主 run 已 turnClosed，压住 settle 等 onRunSettled 复查。
        context.markTurnClosed();
        Assert.assertTrue(adapter.onBackgroundTaskCompleted(event("s-active", "completed")));
    }

    @Test
    public void hitlYieldTakesOverWithoutWaking() {
        adapter = newAdapter(config(true), new ActiveAgentRunRegistry(), new SessionYieldGate() {
            @Override
            public boolean hasOpenYield(String sessionId) {
                return true;
            }
        });
        Assert.assertTrue(adapter.onBackgroundTaskCompleted(event("s-hitl", "completed")));
    }

    @Test
    public void idleSessionSchedulesWakeWindow() {
        adapter = newAdapter(config(true), new ActiveAgentRunRegistry(), freeGate());
        SessionNotificationHub.offer("s-idle", notification());
        Assert.assertTrue(adapter.onBackgroundTaskCompleted(event("s-idle", "completed")));
        Assert.assertTrue(SessionNotificationHub.hasPending("s-idle"));
    }

    private BackgroundTaskWakeAdapter newAdapter(CuteConfig config,
                                                 ActiveAgentRunRegistry registry,
                                                 SessionYieldGate gate) {
        return new BackgroundTaskWakeAdapter(
                registry, gate, null, new SessionProjectionRegistry(), config);
    }

    private static CuteConfig config(boolean enabled) {
        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(enabled);
        // 合并窗口调大：测试只验证判活，不让调度线程真正派发。
        config.setBackgroundAutoContinuationDelayMs(3_600_000L);
        return config;
    }

    private static SessionYieldGate freeGate() {
        return new SessionYieldGate() {
            @Override
            public boolean hasOpenYield(String sessionId) {
                return false;
            }
        };
    }

    private static BackgroundTaskCompletionEvent event(String sessionId, String status) {
        return new BackgroundTaskCompletionEvent(
                sessionId, "r-" + sessionId, "t-1", "agent-1", "child-1", status, "desc");
    }

    private static TaskNotification notification() {
        return new TaskNotification("t-1", "agent-1", "child-1", null, "desc",
                RuntimeBackgroundTask.STATUS_COMPLETED, "ok", null, 1, 10L, null, 0L);
    }
}
