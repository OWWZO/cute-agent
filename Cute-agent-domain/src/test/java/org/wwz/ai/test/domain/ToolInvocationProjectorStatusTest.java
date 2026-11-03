package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.CanvasPublishToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DataAnalysisToolOutput;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.CanvasPublishToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DataAnalysisToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.FileToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.Map;

/**
 * 专用投影器必须按调用状态分流：失败调用不能回放成成功卡片。
 */
public class ToolInvocationProjectorStatusTest {

    @Test
    public void failedCanvasPublishIsReplayedAsToolResultWithError() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-canvas-fail")
                .toolName("canvas_publish")
                .status(org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_FAILED)
                .errorMsg("html_path not found")
                .structuredOutput(CanvasPublishToolOutput.builder()
                        .title("Canvas")
                        .mode("html")
                        .build())
                .build();

        List<ProjectedReplayEvent> events = new CanvasPublishToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        Map<String, Object> envelope = castMap(events.get(0).getResultMap());
        Assert.assertEquals("tool_result", envelope.get("messageType"));
        Map<String, Object> toolResult = castMap(envelope.get("toolResult"));
        Assert.assertEquals("html_path not found", toolResult.get("toolResult"));
    }

    @Test
    public void successfulCanvasPublishStillProjectsHtmlEvent() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-canvas-ok")
                .toolName("canvas_publish")
                .status(org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(CanvasPublishToolOutput.builder()
                        .title("页面")
                        .mode("html")
                        .build())
                .build();

        List<ProjectedReplayEvent> events = new CanvasPublishToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Map<String, Object> envelope = castMap(events.get(0).getResultMap());
        Assert.assertEquals("html", envelope.get("messageType"));
        Assert.assertEquals("发布画布", castMap(envelope.get("resultMap")).get("command"));
    }

    @Test
    public void failedDataAnalysisIsReplayedAsToolResultWithError() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-data-fail")
                .toolName("data_analysis")
                .status(org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_FAILED)
                .errorMsg("ERR-DATA-500")
                .structuredOutput(DataAnalysisToolOutput.builder()
                        .content("")
                        .summary("data_analysis 执行失败：远端超时")
                        .build())
                .build();

        List<ProjectedReplayEvent> events = new DataAnalysisToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        Map<String, Object> envelope = castMap(events.get(0).getResultMap());
        Assert.assertEquals("tool_result", envelope.get("messageType"));
        Assert.assertEquals("ERR-DATA-500", castMap(envelope.get("toolResult")).get("toolResult"));
    }

    @Test
    public void successfulDataAnalysisStillProjectsAnalysisEvent() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-data-ok")
                .toolName("data_analysis")
                .status(org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(DataAnalysisToolOutput.builder()
                        .content("销售额同比上升 12%")
                        .build())
                .build();

        List<ProjectedReplayEvent> events = new DataAnalysisToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Map<String, Object> envelope = castMap(events.get(0).getResultMap());
        Assert.assertEquals("data_analysis", envelope.get("messageType"));
        Assert.assertEquals("销售额同比上升 12%", castMap(envelope.get("resultMap")).get("data"));
    }

    @Test
    public void legacyFileToolWithoutStructuredOutputFallsBackToToolResultText() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .toolCallId("call-file-legacy")
                .toolName("file_tool")
                .status(org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS)
                .llmObservation("已写入 report.md")
                .build();

        List<ProjectedReplayEvent> events = new FileToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        Map<String, Object> envelope = castMap(events.get(0).getResultMap());
        Assert.assertEquals("tool_result", envelope.get("messageType"));
        Assert.assertEquals("已写入 report.md", castMap(envelope.get("toolResult")).get("toolResult"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
