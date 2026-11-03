package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 被拒绝/提前取消的工具批次也必须走预登记与 running 事件，保证前后端事件成对、账本有记录。
 */
public class ToolExecutionPipelineYieldBatchTest {

    private AgentExecutionRecorder recorder;
    private AgentContext context;
    private Printer printer;
    private ToolExecutionPipeline pipeline;

    @Test
    public void mixedYieldBatchIsPreRegisteredAndEmitsRunningBeforeFinished() {
        setUpHarness();

        pipeline.executeBatch(List.of(
                newToolCall("call-ask", AskUserQuestionTool.NAME),
                newToolCall("call-demo", "demo_tool")));

        verify(recorder).createToolInvocations(any());
        Map<String, Long> statuses = emittedStatusCounts();
        Assert.assertEquals(Long.valueOf(2), statuses.get("running"));
        Assert.assertEquals(Long.valueOf(2), statuses.get("failed"));
    }

    @Test
    public void cancelledBeforeStartEmitsRunningAndCancelled() {
        setUpHarness();
        RunCancellation token = new RunCancellation();
        context.control().bindCancellation(token);
        token.cancel("user_stop");

        pipeline.executeBatch(List.of(newToolCall("call-demo", "demo_tool")));

        verify(recorder).createToolInvocations(any());
        Map<String, Long> statuses = emittedStatusCounts();
        Assert.assertEquals(Long.valueOf(1), statuses.get("running"));
        Assert.assertEquals(Long.valueOf(1), statuses.get("cancelled"));
    }

    private Map<String, Long> emittedStatusCounts() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(printer, atLeastOnce()).send(anyString(), eq("tool_call"),
                payloadCaptor.capture(), anyBoolean());
        return payloadCaptor.getAllValues().stream()
                .map(payload -> String.valueOf(((Map<?, ?>) payload).get("status")))
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.stream.Collectors.counting()));
    }

    private void setUpHarness() {
        recorder = mock(AgentExecutionRecorder.class);
        when(recorder.createToolInvocations(any())).thenReturn(Map.of());
        AgentRunState runState = AgentRunState.builder().runId(42L).build();
        runState.bindCurrentLlmInvocationId(900L);
        context = AgentContext.builder()
                .requestId("req-yield")
                .sessionId("session-yield")
                .executionRecorder(recorder)
                .agentRunState(runState)
                .build();
        printer = mock(Printer.class);

        BaseAgent agent = mock(BaseAgent.class);
        when(agent.getContext()).thenReturn(context);
        when(agent.getPrinter()).thenReturn(printer);
        when(agent.getName()).thenReturn("react");
        when(agent.getCurrentStep()).thenReturn(1);
        when(agent.getAvailableTools()).thenReturn(tools());
        when(agent.buildFinalLlmObservation(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        pipeline = new ToolExecutionPipeline(agent);
    }

    private ToolCollection tools() {
        ToolCollection tools = new ToolCollection();
        tools.setAgentContext(context);
        for (String name : List.of(AskUserQuestionTool.NAME, "demo_tool")) {
            tools.addTool(new StubTool(name));
        }
        return tools;
    }

    private static ToolCall newToolCall(String toolCallId, String toolName) {
        return ToolCall.builder()
                .id(toolCallId)
                .type("function")
                .function(ToolCall.Function.builder()
                        .name(toolName)
                        .arguments("{}")
                        .build())
                .build();
    }

    private static final class StubTool implements BaseTool {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name;
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of();
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.fromData(Map.of());
        }
    }
}
