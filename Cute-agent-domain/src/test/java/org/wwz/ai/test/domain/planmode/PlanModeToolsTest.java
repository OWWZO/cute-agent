package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeState;
import org.wwz.ai.domain.agent.runtime.prompt.PlanSolvePrompt;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;

/**
 * TodoWrite / Plan Mode 核心存储与状态测试（避开 AgentContext @Slf4j 在 domain 单测 classpath 的 logback 冲突）。
 */
public class PlanModeToolsTest {

    @Test
    public void taskStopStopsRunningBackgroundTask() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask bg = registry.register(RuntimeBackgroundTask.TYPE_LOCAL_AGENT, "explore", null);
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_RUNNING, bg.getStatus());

        RuntimeBackgroundTask stopped = registry.stop(bg.getId()).orElseThrow();
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_STOPPED, stopped.getStatus());
        Assert.assertNotNull(stopped.getEndedAtMs());
    }

    @Test
    public void enterAndExitPlanMode() {
        PlanModeState state = PlanModeState.builder().build();
        Assert.assertFalse(state.isPlanMode());

        state.enterPlanMode();
        Assert.assertTrue(state.isPlanMode());
        Assert.assertEquals(PlanModeState.MODE_DEFAULT, state.getPrePlanMode());

        state.setPlan("## Steps\n1. A\n2. B", null);
        String restored = state.exitPlanMode();
        Assert.assertEquals(PlanModeState.MODE_DEFAULT, restored);
        Assert.assertFalse(state.isPlanMode());
        Assert.assertTrue(state.isHasExitedPlanMode());
        Assert.assertTrue(state.getPlanContent().contains("Steps"));
    }

    @Test
    public void requestExitKeepsPlanModeUntilExit() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();
        state.requestExitWithPlan("plan body", "p.md");
        Assert.assertTrue(state.isPlanMode());
        Assert.assertTrue(state.isExitPendingApproval());
        state.clearPendingApproval();
        Assert.assertFalse(state.isExitPendingApproval());
        Assert.assertTrue(state.isPlanMode());
    }

    @Test
    public void exitOutsidePlanModeDoesNotDowngradeCurrentMode() {
        PlanModeState state = PlanModeState.builder()
                .mode(PlanModeState.MODE_ACCEPT_EDITS)
                .prePlanMode(PlanModeState.MODE_DEFAULT)
                .build();

        Assert.assertEquals(PlanModeState.MODE_ACCEPT_EDITS, state.exitPlanMode());
        Assert.assertEquals(PlanModeState.MODE_ACCEPT_EDITS, state.getMode());
        Assert.assertFalse(state.isHasExitedPlanMode());
        Assert.assertFalse(state.isNeedsPlanModeExitAttachment());
    }

    @Test
    public void planModeIsNotAValidRestoreTarget() {
        PlanModeState state = PlanModeState.builder()
                .mode(PlanModeState.MODE_PLAN)
                .prePlanMode(PlanModeState.MODE_PLAN)
                .build();

        Assert.assertEquals(PlanModeState.MODE_DEFAULT, state.exitPlanMode());
        Assert.assertEquals(PlanModeState.MODE_DEFAULT, state.getMode());
        Assert.assertTrue(state.isHasExitedPlanMode());
    }

    @Test
    public void exitWhenAlreadyDefaultIsIdempotentEnter() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();
        state.enterPlanMode();
        Assert.assertTrue(state.isPlanMode());
    }

    @Test
    public void approvedPlanUsesExecutionGuidance() {
        String prompt = PlanSolvePrompt.ensureApprovedExecution("base prompt");

        Assert.assertTrue(prompt.contains(PlanSolvePrompt.EXECUTION_MARKER));
        Assert.assertTrue(prompt.contains("Plan mode is not active this turn"));
        Assert.assertTrue(prompt.contains("Call EnterPlanMode"));
    }

    @Test
    public void toolNamesMatchCchaha() {
        Assert.assertEquals("TodoWrite", TaskToolNames.TODO_WRITE);
        Assert.assertEquals("TaskStop", TaskToolNames.TASK_STOP);
        Assert.assertEquals("TaskOutput", TaskToolNames.TASK_OUTPUT);
        Assert.assertEquals("EnterPlanMode", TaskToolNames.ENTER_PLAN_MODE);
        Assert.assertEquals("ExitPlanMode", TaskToolNames.EXIT_PLAN_MODE);
    }

    @Test
    public void todoWriteKeepsCompletedItemsUntilExplicitClear() {
        SessionTaskListStore store = new SessionTaskListStore("session-3");
        store.replaceAll(java.util.List.of(
                java.util.Map.of("content", "任务A", "status", "pending"),
                java.util.Map.of("content", "任务B", "status", "in_progress")
        ));
        Assert.assertEquals(2, store.list().size());
        store.replaceAll(java.util.List.of(
                java.util.Map.of("content", "任务A", "status", "completed"),
                java.util.Map.of("content", "任务B", "status", "completed")
        ));
        Assert.assertEquals(2, store.list().size());
        Assert.assertEquals("completed", store.list().get(0).getStatus());
        Assert.assertEquals("completed", store.list().get(1).getStatus());

        store.replaceAll(java.util.List.of());
        Assert.assertEquals(0, store.list().size());
    }

    @Test
    public void taskStopMissingIdReturnsEmpty() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        Assert.assertTrue(registry.stop("no-such").isEmpty());
        Assert.assertTrue(registry.get("no-such").isEmpty());
    }
}
