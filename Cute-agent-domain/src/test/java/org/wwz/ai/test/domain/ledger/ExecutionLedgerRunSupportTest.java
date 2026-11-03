package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * run 终态写回必须按激活时捕获的 run 身份定位，而不是读取可能被改写过的 requestId。
 */
public class ExecutionLedgerRunSupportTest {

    @Test
    public void finishRunUsesRunUidCapturedAtActivation() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        AgentContext context = AgentContext.builder()
                .requestId("req-new")
                .executionRecorder(recorder)
                .build();
        context.activateLedgerRun(42L, "req-original");
        // 模拟子 Agent 旧续跑路径：激活后改写 context.requestId。
        context.setRequestId("req-new");

        ExecutionLedgerRunSupport.finishRun(
                context, ExecutionLedgerConstants.STATUS_SUCCESS, "完成", null, null);

        ArgumentCaptor<DialogueRunFinishRecord> captor =
                ArgumentCaptor.forClass(DialogueRunFinishRecord.class);
        verify(recorder).finishRun(captor.capture());
        Assert.assertEquals("req-original", captor.getValue().getRequestId());
        Assert.assertEquals(Long.valueOf(42L), captor.getValue().getRunId());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS),
                captor.getValue().getStatus());
    }
}
