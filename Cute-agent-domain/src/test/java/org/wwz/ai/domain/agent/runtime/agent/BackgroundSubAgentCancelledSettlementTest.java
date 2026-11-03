package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TaskStop 回归：取消异常路径也必须结算父侧工具账本行，不能永远停在 running。
 */
public class BackgroundSubAgentCancelledSettlementTest {

    private static final String TOOL_CALL_ID = "call-cancelled";
    private static final long TOOL_INVOCATION_ID = 777L;

    @After
    public void cleanup() {
        SessionAgentMailboxHub.clearAll();
    }

    @Test
    public void cancelledRunSettlesParentToolInvocation() throws Exception {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentRunState runState = AgentRunState.builder().runId(42L).build();
        runState.bindToolInvocationIds(Map.of(TOOL_CALL_ID, TOOL_INVOCATION_ID));
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        AgentContext parent = AgentContext.builder()
                .requestId("req-cancel-settle")
                .sessionId("sess-cancel-settle")
                .executionRecorder(recorder)
                .agentRunState(runState)
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();

        CountDownLatch started = new CountDownLatch(1);
        SubAgentRunner runner = Mockito.mock(SubAgentRunner.class);
        Mockito.when(runner.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    Thread.sleep(10_000L);
                    return SubAgentResult.builder()
                            .status(SubAgentResult.STATUS_COMPLETED)
                            .agentId("agent-cancelled")
                            .build();
                });

        ToolResultPayload ack = BackgroundSubAgentExecutor.submit(parent, runner,
                new ActiveAgentRunRegistry(), "desc", "prompt", "general-purpose",
                "agent-cancelled", TOOL_CALL_ID, AgentDispatchTool.NAME);
        Assert.assertEquals(Boolean.TRUE, ((Map<?, ?>) ack.getLlmData()).get("ok"));
        Assert.assertTrue(started.await(5, TimeUnit.SECONDS));

        RuntimeBackgroundTask task = registry.findLatestByAgentId("agent-cancelled").orElseThrow();
        registry.stop(task.getId());

        ArgumentCaptor<ToolInvocationFinishRecord> captor =
                ArgumentCaptor.forClass(ToolInvocationFinishRecord.class);
        Mockito.verify(recorder, Mockito.timeout(3_000).times(1))
                .finishToolInvocation(captor.capture());
        ToolInvocationFinishRecord settlement = captor.getValue();
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_STOPPED), settlement.getStatus());
        Assert.assertNotNull(settlement.getFinishedAt());
        Assert.assertEquals(TOOL_CALL_ID, settlement.getToolCallId());
    }
}
