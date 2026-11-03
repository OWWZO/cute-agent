package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.CanvasPublishToolOutput;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.CanvasPublishToolInvocationProjector;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
