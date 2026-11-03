package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 直接调用 preRegister 时可能混入空白 toolCallId；这类命令没有计时条目，
 * 必须在进入 timing 解引用之前被过滤。
 */
public class ToolLedgerRecorderBlankIdTest {

    @Test
    public void blankToolCallIdShouldBeSkippedInsteadOfNpe() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        AgentRunState runState = AgentRunState.builder().runId(10L).build();
        runState.bindCurrentLlmInvocationId(20L);
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .agentRunState(runState)
                .executionRecorder(recorder)
                .build();
        TestAgent agent = new TestAgent();
        agent.setName("tester");
        agent.setContext(context);
        when(recorder.createToolInvocations(any())).thenReturn(Map.of("call-1", 1L));

        ToolLedgerRecorder ledger = new ToolLedgerRecorder(agent, new ToolTimingTracker());
        Map<String, Long> created = ledger.preRegister(
                List.of(toolCall(" "), toolCall("call-1")),
                Map.of());

        Assert.assertEquals(Map.of("call-1", 1L), created);
        ArgumentCaptor<ToolInvocationBatchStartRecord> captor =
                ArgumentCaptor.forClass(ToolInvocationBatchStartRecord.class);
        verify(recorder).createToolInvocations(captor.capture());
        Assert.assertEquals(1, captor.getValue().getItems().size());
        Assert.assertEquals("call-1", captor.getValue().getItems().get(0).getToolCallId());
    }

    private ToolCall toolCall(String id) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder().name("tool").arguments("{}").build())
                .build();
    }

    private static final class TestAgent extends BaseAgent {

        @Override
        public String step() {
            return "";
        }
    }
}
