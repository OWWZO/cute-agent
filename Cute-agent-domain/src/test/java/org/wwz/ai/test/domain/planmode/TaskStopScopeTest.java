package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskStopTool;

import java.util.Map;

public class TaskStopScopeTest {

    @Test
    public void defaultStopDoesNotTouchGrandchild() {
        Fixture fixture = new Fixture();
        ToolResultPayload payload = (ToolResultPayload) new TaskStopTool().execute(
                Map.of("agent_id", "child"), fixture.rootContext);

        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        Map<?, ?> data = (Map<?, ?>) payload.getLlmData();
        Assert.assertEquals(Boolean.FALSE, data.get("recursive"));
        Assert.assertEquals(1, ((java.util.List<?>) data.get("stoppedAgentIds")).size());
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_STOPPED,
                fixture.registry.findLatestByAgentId("child").orElseThrow().getStatus());
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_RUNNING,
                fixture.registry.findLatestByAgentId("grandchild").orElseThrow().getStatus());
    }

    @Test
    public void recursiveStopTouchesGrandchild() {
        Fixture fixture = new Fixture();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("agent_id", "child");
        params.put("recursive", true);
        ToolResultPayload payload = (ToolResultPayload) new TaskStopTool().execute(
                params, fixture.rootContext);

        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        Map<?, ?> data = (Map<?, ?>) payload.getLlmData();
        Assert.assertEquals(Boolean.TRUE, data.get("recursive"));
        Assert.assertEquals(2, ((java.util.List<?>) data.get("stoppedAgentIds")).size());
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_STOPPED,
                fixture.registry.findLatestByAgentId("grandchild").orElseThrow().getStatus());
    }

    @Test
    public void rootCannotStopGrandchild() {
        Fixture fixture = new Fixture();
        ToolResultPayload payload = (ToolResultPayload) new TaskStopTool().execute(
                Map.of("agent_id", "grandchild"), fixture.rootContext);

        Assert.assertEquals(Boolean.TRUE, payload.getFailed());
        Assert.assertEquals("not_owned", payload.getErrorMsg());
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_RUNNING,
                fixture.registry.findLatestByAgentId("grandchild").orElseThrow().getStatus());
    }

    private static final class Fixture {
        private final RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        private final AgentContext rootContext;

        private Fixture() {
            RuntimeBackgroundTask child = task("child", "root");
            task("grandchild", "child");
            rootContext = context("root", null);
            Assert.assertNotNull(child);
        }

        private RuntimeBackgroundTask task(String agentId, String parentAgentId) {
            RuntimeBackgroundTask task = registry.registerLocalAgent(agentId, "general-purpose", "p");
            registry.bindAgentId(task.getId(), agentId);
            task.setParentAgentId(parentAgentId);
            task.setOwnerAgentId(parentAgentId);
            task.setChildSessionId(SubAgentContextFactory.newChildSessionId(agentId));
            return task;
        }

        private AgentContext context(String sessionId, String subAgentId) {
            AgentContext context = AgentContext.builder()
                    .requestId("req-" + sessionId)
                    .sessionId(sessionId)
                    .sessionControl(new SessionControl().withBackgroundTasks(registry))
                    .build();
            if (subAgentId != null) {
                context.setSubAgentIdentity(subAgentId, "general-purpose", null, null);
            }
            return context;
        }
    }
}
