package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.Map;

public class DeepSearchToolInvocationProjectorTest {

    @Test
    public void runningInvocationWithoutStructuredOutputStillProjectsToolCard() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .id(1L)
                .toolCallId("call-deep-search")
                .toolName("deep_search")
                .inputJson("{\"query\":\"新能源汽车行业研究\",\"reportFileName\":\"行业报告.md\"}")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .build();

        List<ProjectedReplayEvent> events = new DeepSearchToolInvocationProjector()
                .project(invocation, List.of(), new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        Map<String, Object> response = events.get(0).getResultMap();
        Assert.assertEquals("deep_search", response.get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stage = (Map<String, Object>) response.get("resultMap");
        Assert.assertEquals("extend", stage.get("messageType"));
        Assert.assertEquals(Boolean.FALSE, stage.get("isFinal"));
        Assert.assertEquals("call-deep-search", stage.get("toolCallId"));
        Assert.assertEquals(Boolean.FALSE, response.get("isFinal"));
        Assert.assertEquals(Boolean.FALSE, response.get("finish"));
        Assert.assertEquals("running", response.get("status"));
    }

    @Test
    public void runningInvocationIgnoresInternalSearchArtifactInFileInfoForRefresh() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .id(2L)
                .toolCallId("call-deep-search-artifact")
                .toolName("deep_search")
                .inputJson("{\"query\":\"新能源汽车行业研究\"}")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .build();
        ArtifactView searchResultArtifact = ArtifactView.builder()
                .fileName("新能源汽车行业研究_search_result.txt")
                .visibility(ExecutionLedgerConstants.VISIBILITY_INTERNAL)
                .storageKey("https://files.example.com/search-result.txt")
                .downloadUrl("https://files.example.com/search-result.txt")
                .previewUrl("https://preview.example.com/search-result.txt")
                .build();
        ArtifactView reportArtifact = ArtifactView.builder()
                .fileName("新能源汽车行业研究.md")
                .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                .storageKey("https://files.example.com/report.md")
                .downloadUrl("https://files.example.com/report.md")
                .previewUrl("https://preview.example.com/report.md")
                .build();

        List<ProjectedReplayEvent> events = new DeepSearchToolInvocationProjector()
                .project(invocation, List.of(searchResultArtifact, reportArtifact), new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) events.get(0)
                .getResultMap()
                .get("resultMap");
        Assert.assertTrue(nested.get("fileInfo") instanceof List<?>);
        Assert.assertEquals(
                "新能源汽车行业研究.md",
                ((Map<?, ?>) ((List<?>) nested.get("fileInfo")).get(0)).get("fileName")
        );
        Assert.assertEquals(1, ((List<?>) nested.get("fileInfo")).size());
    }

    @Test
    public void completedReportUsesVisibleMarkdownArtifactAsPrimaryFile() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .id(3L)
                .toolCallId("call-deep-search-report")
                .toolName("deep_search")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(DeepSearchToolOutput.of(
                        "新能源汽车行业研究",
                        "完整报告",
                        List.of(DeepSearchStage.report("完整报告")),
                        List.of()))
                .build();
        ArtifactView searchResultArtifact = ArtifactView.builder()
                .fileName("新能源汽车行业研究_search_result.txt")
                .visibility(ExecutionLedgerConstants.VISIBILITY_INTERNAL)
                .downloadUrl("https://files.example.com/search-result.txt")
                .previewUrl("https://preview.example.com/search-result.txt")
                .build();
        ArtifactView reportArtifact = ArtifactView.builder()
                .fileName("新能源汽车行业研究.md")
                .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                .downloadUrl("https://files.example.com/report.md")
                .previewUrl("https://preview.example.com/report.md")
                .build();

        List<ProjectedReplayEvent> events = new DeepSearchToolInvocationProjector()
                .project(invocation, List.of(searchResultArtifact, reportArtifact),
                        new AgentStreamAccumulator());

        Assert.assertEquals(1, events.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) events.get(0)
                .getResultMap()
                .get("resultMap");
        Assert.assertEquals("report", nested.get("messageType"));
        List<?> fileInfo = (List<?>) nested.get("fileInfo");
        Assert.assertEquals(1, fileInfo.size());
        Assert.assertEquals(
                "新能源汽车行业研究.md",
                ((Map<?, ?>) fileInfo.get(0)).get("fileName")
        );
    }
}
