package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AgentListTool;

import java.util.List;
import java.util.Map;

public class AgentListToolTest {

    @Test
    public void listsOnlyDirectChildrenAndHidesGrandchildren() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        task(registry, "child-1", "root");
        task(registry, "grandchild", "child-1");

        Map<?, ?> data = execute(registry, "sess-root", "root", Map.of());

        Assert.assertEquals("root", data.get("callerAgentId"));
        List<?> agents = (List<?>) data.get("agents");
        Assert.assertEquals(1, agents.size());
        Assert.assertEquals("child-1", ((Map<?, ?>) agents.get(0)).get("agentId"));
    }

    @Test
    public void childSeesItsOwnChildren() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        task(registry, "child-1", "root");
        task(registry, "grandchild", "child-1");

        Map<?, ?> data = execute(registry, "sess-root", "child-1", Map.of());

        List<?> agents = (List<?>) data.get("agents");
        Assert.assertEquals(1, agents.size());
        Assert.assertEquals("grandchild", ((Map<?, ?>) agents.get(0)).get("agentId"));
    }

    @Test
    public void keepsOnlyLatestRunPerAgentAndFiltersTerminal() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask latest = task(registry, "child-1", "root");
        latest.setStartedAtMs(2_000L);
        RuntimeBackgroundTask old = task(registry, "child-1", "root");
        old.setStartedAtMs(1_000L);
        RuntimeBackgroundTask terminal = task(registry, "child-2", "root");
        registry.stop(terminal.getId());

        Map<?, ?> all = execute(registry, "sess-root", "root", Map.of());
        List<?> agents = (List<?>) all.get("agents");
        Assert.assertEquals(2, agents.size());
        Map<?, ?> child = agents.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "child-1".equals(item.get("agentId")))
                .findFirst()
                .orElseThrow();
        Assert.assertEquals(2_000L, child.get("startedAtMs"));

        Map<?, ?> activeOnly = execute(registry, "sess-root", "root", Map.of("includeTerminal", false));
        List<?> activeAgents = (List<?>) activeOnly.get("agents");
        Assert.assertEquals(1, activeAgents.size());
        Assert.assertEquals("child-1", ((Map<?, ?>) activeAgents.get(0)).get("agentId"));
    }

    @Test
    public void limitTruncatesResult() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        task(registry, "child-1", "root");
        task(registry, "child-2", "root");

        Map<?, ?> data = execute(registry, "sess-root", "root", Map.of("limit", 1));

        Assert.assertEquals(Boolean.TRUE, data.get("truncated"));
        Assert.assertEquals(1, data.get("count"));
        Assert.assertEquals(1, ((List<?>) data.get("agents")).size());
    }

    @Test
    public void doesNotLeakPromptOrOutput() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask child = task(registry, "child-1", "root");
        child.setOutput("secret output");

        Map<?, ?> data = execute(registry, "sess-root", "root", Map.of());
        Map<?, ?> agent = (Map<?, ?>) ((List<?>) data.get("agents")).get(0);

        Assert.assertFalse(agent.containsKey("output"));
        Assert.assertFalse(agent.containsKey("prompt"));
    }

    private static RuntimeBackgroundTask task(RuntimeBackgroundTaskRegistry registry,
                                              String agentId,
                                              String ownerAgentId) {
        RuntimeBackgroundTask task = registry.registerLocalAgent(agentId, "general-purpose", "prompt");
        registry.bindAgentId(task.getId(), agentId);
        task.setOwnerAgentId(ownerAgentId);
        task.setParentAgentId(ownerAgentId);
        task.setStartedAtMs(System.currentTimeMillis());
        return task;
    }

    private static Map<?, ?> execute(RuntimeBackgroundTaskRegistry registry,
                                     String sessionId,
                                     String subAgentId,
                                     Map<String, Object> params) {
        AgentContext context = AgentContext.builder()
                .requestId("req-" + sessionId)
                .sessionId(sessionId)
                .subAgentId(subAgentId)
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();
        ToolResultPayload payload = (ToolResultPayload) new AgentListTool().execute(params, context);
        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        return (Map<?, ?>) payload.getLlmData();
    }
}
