package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteLlmDependencies;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import static org.mockito.Mockito.mock;

public class SubAgentRunnerResumeSelfRejectionTest {

    private static final String SESSION_ID = "sess-resume-self-reject";
    private static final String AGENT_ID = "agentresumeself01";

    @Test
    public void resumeDoesNotRejectItsOwnRunningTask() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry(SESSION_ID, null);
        RuntimeBackgroundTask task = registry.registerLocalAgent("resume", "general-purpose", "prompt");
        task.setAgentId(AGENT_ID);
        AgentContext parent = parentContext(registry);

        Assert.assertFalse(resumeRejectsRunningTask(parent, AGENT_ID, task.getId()));
        Assert.assertTrue(resumeRejectsRunningTask(parent, AGENT_ID, "some-other-task"));
    }

    private static boolean resumeRejectsRunningTask(AgentContext parent,
                                                    String agentId,
                                                    String excludeTaskId) {
        try {
            java.lang.reflect.Method method = SubAgentRunner.class.getDeclaredMethod(
                    "isSubAgentStillRunning", AgentContext.class, String.class, String.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, parent, agentId, excludeTaskId));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AgentContext parentContext(RuntimeBackgroundTaskRegistry registry) {
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "reactMaxSteps", 1);
        ReflectionTestUtils.setField(config, "maxObserve", "0");
        ReflectionTestUtils.setField(config, "digitalEmployeePrompt", "");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("llm.default.base_url", "https://test.local");
        CuteLlmDependencies llmDependencies = CuteLlmDependencies.builder()
                .completionPort(mock(LlmCompletionPort.class))
                .streamResponseHandler(new StreamResponseHandler())
                .build();
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .cuteConfig(config)
                .environment(environment)
                .llmDependencies(llmDependencies)
                .build();
        return AgentContext.builder()
                .requestId("req-resume-self-reject")
                .sessionId(SESSION_ID)
                .query("resume")
                .model("test-model")
                .toolCollection(new ToolCollection())
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .runtimeDependencies(dependencies)
                .build();
    }
}
