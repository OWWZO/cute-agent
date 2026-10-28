package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskOutputTool;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.wwz.ai.domain.agent.runtime.cancel.RunControl;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;

/**
 * TaskOutput：按 agentId 寻址（task_id 不对模型暴露）；block=false 立即 peek；block=true 等到终态。
 */
public class TaskOutputToolTest {

    @Test
    public void schemaUsesAgentIdNotTaskId() {
        Map<String, Object> params = new TaskOutputTool().toParams();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        Assert.assertFalse(properties.containsKey("timeout"));
        Assert.assertTrue(properties.containsKey("agent_id"));
        Assert.assertFalse("task_id 不再对模型暴露", properties.containsKey("task_id"));
        Assert.assertTrue(properties.containsKey("block"));
        Assert.assertFalse(new TaskOutputTool().getDescription().contains("timeout"));
    }

    @Test
    public void peekWhileRunningReturnsNotReady() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = registry.registerLocalAgent("peek", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-peek");
        TaskOutputTool tool = new TaskOutputTool();
        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "agent_id", "agent-peek",
                "block", false), AgentContext.builder()
                .requestId("req-to")
                .sessionId("sess-to")
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.getLlmData();
        Assert.assertEquals("not_ready", data.get("retrieval_status"));
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_RUNNING, data.get("status"));
        Assert.assertEquals("agent-peek", data.get("agent_id"));
    }

    @Test
    public void lostTaskReturnsExplicitRestartMessage() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = registry.registerLocalAgent("lost", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-lost");
        task.setStatus(RuntimeBackgroundTask.STATUS_LOST);
        task.setErrorMsg("server restarted");
        TaskOutputTool tool = new TaskOutputTool();

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "agent_id", "agent-lost",
                "block", false), AgentContext.builder()
                .requestId("req-to-lost")
                .sessionId("sess-to-lost")
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.getLlmData();
        Assert.assertEquals("success", data.get("retrieval_status"));
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_LOST, data.get("status"));
        Assert.assertEquals("任务已丢失：server restarted", data.get("message"));
    }

    @Test
    public void blockTrueReturnsAfterComplete() throws Exception {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = registry.registerLocalAgent("block", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-block");
        TaskOutputTool tool = new TaskOutputTool();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> f = pool.submit(() -> {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                registry.complete(task.getId(), SubAgentResult.builder()
                        .status(SubAgentResult.STATUS_COMPLETED)
                        .agentId("agent-block")
                        .content("done")
                        .build());
            });
            ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of("agent_id", "agent-block"),
                    AgentContext.builder()
                            .requestId("req-to-block")
                            .sessionId("sess-to-block")
                            .sessionControl(new SessionControl().withBackgroundTasks(registry))
                            .build());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload.getLlmData();
            Assert.assertEquals("success", data.get("retrieval_status"));
            Assert.assertEquals(RuntimeBackgroundTask.STATUS_COMPLETED, data.get("status"));
            Assert.assertEquals("done", data.get("output"));
            f.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 回归：SendMessage 唤醒会为同一 agentId 新建 task。模型手里只有 agentId，
     * 所以 TaskOutput 必须取该 agentId 最近一次运行，而不是报 not_found。
     */
    @Test
    public void resolvesLatestRunOfSameAgent() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask first = registry.registerLocalAgent("first", "general-purpose", "p");
        registry.bindAgentId(first.getId(), "agent-multi");
        registry.complete(first.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-multi")
                .content("old result")
                .build());

        RuntimeBackgroundTask second = registry.registerLocalAgent("second", "general-purpose", "p");
        registry.bindAgentId(second.getId(), "agent-multi");
        registry.complete(second.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-multi")
                .content("new result")
                .build());

        ToolResultPayload payload = (ToolResultPayload) new TaskOutputTool().execute(
                Map.of("agent_id", "agent-multi", "block", false),
                AgentContext.builder()
                        .requestId("req-to-multi")
                        .sessionId("sess-to-multi")
                        .sessionControl(new SessionControl().withBackgroundTasks(registry))
                        .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.getLlmData();

        Assert.assertEquals("new result", data.get("output"));
        Assert.assertEquals("second", data.get("description"));
    }

    @Test
    public void unknownAgentIdReportsNotFound() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        ToolResultPayload payload = (ToolResultPayload) new TaskOutputTool().execute(
                Map.of("agent_id", "agent-missing", "block", false),
                AgentContext.builder()
                        .requestId("req-to-missing")
                        .sessionId("sess-to-missing")
                        .sessionControl(new SessionControl().withBackgroundTasks(registry))
                        .build());

        Assert.assertTrue(Boolean.TRUE.equals(payload.getFailed()));
        Assert.assertTrue(payload.getToolResult().contains("agent-missing"));
    }
}
