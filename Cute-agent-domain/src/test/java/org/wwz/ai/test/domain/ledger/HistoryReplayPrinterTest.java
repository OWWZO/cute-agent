package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.replay.HistoryReplayPrinter;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放兜底结论只在「主会话确实没有终答帧」时追加。
 */
public class HistoryReplayPrinterTest {

    private final HistoryReplayPrinter printer = new HistoryReplayPrinter();

    @Test
    public void subAgentResultFrameDoesNotSuppressFallbackConclusion() {
        AgentStreamResult frame = frame(Map.of(
                "messageType", "result",
                "isFinal", true,
                "parentToolUseId", "call-parent-1"));

        List<AgentStreamResult> result = printer.ensureReadableConclusion(run(), List.of(frame));

        Assert.assertEquals(2, result.size());
        Assert.assertEquals("主会话总结", result.get(1).getContent());
    }

    @Test
    public void nonFinalResultFrameDoesNotSuppressFallbackConclusion() {
        AgentStreamResult frame = frame(Map.of(
                "messageType", "result",
                "isFinal", false));

        List<AgentStreamResult> result = printer.ensureReadableConclusion(run(), List.of(frame));

        Assert.assertEquals(2, result.size());
    }

    @Test
    public void mainFinalResultFrameSuppressesFallbackConclusion() {
        AgentStreamResult frame = frame(Map.of(
                "messageType", "result",
                "isFinal", true));

        List<AgentStreamResult> result = printer.ensureReadableConclusion(run(), List.of(frame));

        Assert.assertEquals(1, result.size());
    }

    @Test
    public void taskSummaryDoesNotSuppressFallbackConclusion() {
        AgentStreamResult frame = frame(Map.of(
                "messageType", "task_summary",
                "isFinal", true));

        List<AgentStreamResult> result = printer.ensureReadableConclusion(run(), List.of(frame));

        Assert.assertEquals(2, result.size());
        Assert.assertEquals("主会话总结", result.get(1).getContent());
    }

    private static DialogueRunView run() {
        return DialogueRunView.builder()
                .requestId("req-1")
                .finalSummaryText("主会话总结")
                .build();
    }

    private static AgentStreamResult frame(Map<String, Object> nestedResultMap) {
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("taskId", "task-1");
        eventData.put("resultMap", nestedResultMap);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("eventData", eventData);
        return AgentStreamResult.builder()
                .eventData(outer)
                .build();
    }
}
