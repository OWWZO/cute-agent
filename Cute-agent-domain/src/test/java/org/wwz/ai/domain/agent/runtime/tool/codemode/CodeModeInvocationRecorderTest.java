package org.wwz.ai.domain.agent.runtime.tool.codemode;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CodeModeInvocationRecorderTest {

    @Test
    public void beginCarriesCurrentAgentPosition() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        AgentRunState runState = AgentRunState.builder().runId(10L).build();
        runState.markExecutionPosition("react", 3);
        runState.bindCurrentLlmInvocationId(20L);
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .agentRunState(runState)
                .executionRecorder(recorder)
                .build();
        when(recorder.createToolInvocations(any())).thenReturn(Map.of());

        CodeModeInvocationRecorder.begin(
                context, "parent-call", CodeModeInvocationRecorder.TOOL_BROWSER, "{}", LocalDateTime.now());

        ArgumentCaptor<ToolInvocationBatchStartRecord> captor =
                ArgumentCaptor.forClass(ToolInvocationBatchStartRecord.class);
        verify(recorder).createToolInvocations(captor.capture());
        Assert.assertEquals("react", captor.getValue().getAgentName());
        Assert.assertEquals(Integer.valueOf(3), captor.getValue().getStepNo());
    }
}
