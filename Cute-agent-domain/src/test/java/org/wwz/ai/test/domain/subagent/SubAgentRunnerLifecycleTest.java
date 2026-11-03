package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.runtime.CuteLlmDependencies;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子 Agent run 生命周期：resume 早退也要结算账本；取消后不能返回 COMPLETED。
 */
public class SubAgentRunnerLifecycleTest {

    private static final String PARENT_SESSION = "sess-parent";
    private static final String AGENT_ID = "agentlifecycle01";

    @Test
    public void resumeRejectedWhileRunningStillFinishesChildRunAndKeepsMailbox() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        when(recorder.createRun(any())).thenReturn(7L);
        AgentContext parent = parentContext(recorder, null);
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());
        runner.setSessionWorkingMemoryService(mock(SessionWorkingMemoryService.class));
        SessionAgentMailboxHub.markActive(PARENT_SESSION, AGENT_ID, true);
        try {
            SubAgentResult result = runner.run(parent, "desc", "prompt",
                    SubAgentRegistry.TYPE_GENERAL_PURPOSE, AGENT_ID);

            Assert.assertTrue(result.getErrorMsg().contains("仍在运行"));
            ArgumentCaptor<DialogueRunFinishRecord> captor =
                    ArgumentCaptor.forClass(DialogueRunFinishRecord.class);
            verify(recorder).finishRun(captor.capture());
            Assert.assertEquals(Long.valueOf(7L), captor.getValue().getRunId());
            Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED),
                    captor.getValue().getStatus());
            Assert.assertTrue("resume 早退不能清掉另一个运行实例的邮箱",
                    SessionAgentMailboxHub.isActive(PARENT_SESSION, AGENT_ID));
        } finally {
            SessionAgentMailboxHub.markActive(PARENT_SESSION, AGENT_ID, false);
        }
    }

    @Test
    public void cancelledRunReturnsFailedInsteadOfCompleted() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        when(recorder.createRun(any())).thenReturn(8L);
        RunCancellation userStop = new RunCancellation();
        AgentContext parent = parentContext(recorder, userStop);
        userStop.cancel("user_stop");
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        SubAgentResult result = runner.run(parent, "desc", "prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE);

        Assert.assertEquals(SubAgentResult.STATUS_FAILED, result.getStatus());
        Assert.assertTrue(result.getErrorMsg().contains("停止"));
        ArgumentCaptor<DialogueRunFinishRecord> captor =
                ArgumentCaptor.forClass(DialogueRunFinishRecord.class);
        verify(recorder).finishRun(captor.capture());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_STOPPED),
                captor.getValue().getStatus());
    }

    private static AgentContext parentContext(AgentExecutionRecorder recorder, RunCancellation token) {
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "reactMaxSteps", 5);
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
        AgentContext context = AgentContext.builder()
                .requestId("req-parent")
                .sessionId(PARENT_SESSION)
                .query("parent query")
                .model("test-model")
                .executionRecorder(recorder)
                .toolCollection(new ToolCollection())
                .runtimeDependencies(dependencies)
                .build();
        if (token != null) {
            context.control().bindCancellation(token);
        }
        return context;
    }
}
