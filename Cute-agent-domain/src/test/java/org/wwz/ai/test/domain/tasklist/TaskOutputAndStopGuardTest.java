package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskOutputTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskStopTool;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * TaskOutput 必须有界等待且能识别任务消失；TaskStop 不能把「没停到」谎报成成功。
 */
public class TaskOutputAndStopGuardTest {

    @Test
    public void blockWaitUsesBoundedDeadline() {
        AtomicLong observedTimeout = new AtomicLong(-1L);
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry() {
            @Override
            public Optional<RuntimeBackgroundTask> awaitTerminal(String taskId, long timeoutMs) {
                observedTimeout.set(timeoutMs);
                return get(taskId);
            }

            @Override
            public Optional<RuntimeBackgroundTask> awaitUntilTerminal(String taskId, BooleanSupplier abort) {
                throw new AssertionError("TaskOutput 不得再走无界等待");
            }
        };
        RuntimeBackgroundTask task = registry.registerLocalAgent("wait", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-bounded");

        ToolResultPayload payload = (ToolResultPayload) new TaskOutputTool().execute(
                Map.of("agent_id", "agent-bounded"),
                context(registry));

        Assert.assertTrue("必须把 deadline 传给注册表", observedTimeout.get() > 0L);
        Assert.assertTrue(observedTimeout.get() <= 600_000L);
        Assert.assertEquals("not_ready", data(payload).get("retrieval_status"));
    }

    @Test
    public void taskVanishingDuringWaitIsReportedAsNotFound() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry() {
            @Override
            public Optional<RuntimeBackgroundTask> awaitTerminal(String taskId, long timeoutMs) {
                return Optional.empty();
            }

            @Override
            public Optional<RuntimeBackgroundTask> awaitUntilTerminal(String taskId, BooleanSupplier abort) {
                throw new AssertionError("TaskOutput 不得再走无界等待");
            }
        };
        RuntimeBackgroundTask task = registry.registerLocalAgent("gone", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-gone");

        ToolResultPayload payload = (ToolResultPayload) new TaskOutputTool().execute(
                Map.of("agent_id", "agent-gone"),
                context(registry));

        Assert.assertEquals("not_found", data(payload).get("retrieval_status"));
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_LOST, data(payload).get("status"));
    }

    @Test
    public void stopFailureIsNotReportedAsSuccess() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry() {
            @Override
            public Optional<RuntimeBackgroundTask> stop(String taskId) {
                return Optional.empty();
            }
        };
        RuntimeBackgroundTask task = registry.registerLocalAgent("stop", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-stop-gone");

        ToolResultPayload payload = (ToolResultPayload) new TaskStopTool().execute(
                Map.of("agent_id", "agent-stop-gone"),
                context(registry));

        Assert.assertTrue("没停到任何任务时必须失败", Boolean.TRUE.equals(payload.getFailed()));
        Assert.assertTrue(payload.getToolResult().contains("已不存在"));
    }

    private static AgentContext context(RuntimeBackgroundTaskRegistry registry) {
        return AgentContext.builder()
                .requestId("req-task-guard")
                .sessionId("sess-task-guard")
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();
    }

    private static Map<?, ?> data(ToolResultPayload payload) {
        Assert.assertTrue(payload.getLlmData() instanceof Map);
        return (Map<?, ?>) payload.getLlmData();
    }
}
