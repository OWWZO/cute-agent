package org.wwz.ai.domain.agent.ledger.replay;

import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayFactBundle;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplayProjectorFinalAnswerTest {

    private final ReplayProjector projector = new ReplayProjector(
            new ToolInvocationProjectorRegistry(List.of(), new NoopToolProjector()));

    @Test
    public void internalAskWithoutToolsMustNotBecomeResult() {
        LlmInvocationView internalAsk = llm(
                "subagent:deep-search",
                "ask",
                0,
                "**Pipeline fuse_heads calls:** rustoffset_score");

        List<ProjectedReplayEvent> events = projector.projectHistory(ReplayFactBundle.builder()
                .run(run(null, ExecutionLedgerConstants.STATUS_RUNNING))
                .llmInvocations(List.of(internalAsk))
                .build());

        assertTrue(events.isEmpty());
    }

    @Test
    public void runningSubAgentCannotCommitZeroToolResult() {
        LlmInvocationView finalLlm = llm(
                "subagent:deep-search",
                "askTool",
                0,
                "回放中的临时终答");

        List<ProjectedReplayEvent> events = projector.projectHistory(ReplayFactBundle.builder()
                .run(run(null, ExecutionLedgerConstants.STATUS_RUNNING))
                .llmInvocations(List.of(finalLlm))
                .build());

        assertFalse(events.stream().anyMatch(this::isResult));
    }

    @Test
    public void terminalSubAgentZeroToolOutputStillCannotBecomeResult() {
        LlmInvocationView subAgentReply = llm(
                "subagent:Explore",
                "ask",
                0,
                "这是一段子 Agent 普通输出");

        List<ProjectedReplayEvent> events = projector.projectHistory(ReplayFactBundle.builder()
                .run(run("父 run 最终结果", ExecutionLedgerConstants.STATUS_SUCCESS))
                .llmInvocations(List.of(subAgentReply))
                .build());

        assertFalse(events.stream().anyMatch(event -> {
            Map<String, Object> resultMap = event.getResultMap();
            return resultMap != null
                    && "result".equals(resultMap.get("messageType"))
                    && resultMap.get("parentToolUseId") != null;
        }));
    }

    @Test
    public void terminalRunSummaryRemainsTheOnlyResult() {
        LlmInvocationView internalAsk = llm(
                "subagent:deep-search",
                "ask",
                0,
                "内部提取结果");

        List<ProjectedReplayEvent> events = projector.projectHistory(ReplayFactBundle.builder()
                .run(run("子 Agent 最终结果", ExecutionLedgerConstants.STATUS_SUCCESS))
                .llmInvocations(List.of(internalAsk))
                .build());

        List<ProjectedReplayEvent> results = events.stream().filter(this::isResult).toList();
        assertEquals(1, results.size());
        assertEquals("子 Agent 最终结果", results.get(0).getResultMap().get("result"));
    }

    private boolean isResult(ProjectedReplayEvent event) {
        Map<String, Object> resultMap = event == null ? null : event.getResultMap();
        return resultMap != null && "result".equals(resultMap.get("messageType"));
    }

    private LlmInvocationView llm(String agentName,
                                  String callKind,
                                  int toolCallCount,
                                  String responseText) {
        return LlmInvocationView.builder()
                .id(17L)
                .runId(1L)
                .invocationSeq(1)
                .agentName(agentName)
                .callKind(callKind)
                .toolCallCount(toolCallCount)
                .responseText(responseText)
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .build();
    }

    private DialogueRunView run(String finalSummary, int status) {
        return DialogueRunView.builder()
                .id(1L)
                .requestId("req-1")
                .sessionId("session-1")
                .status(status)
                .finalSummaryText(finalSummary)
                .build();
    }

    private static final class NoopToolProjector implements ToolInvocationProjector {

        @Override
        public boolean supports(String toolName) {
            return false;
        }

        @Override
        public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                                  List<ArtifactView> artifacts,
                                                  AgentStreamAccumulator state) {
            return List.of();
        }
    }
}
