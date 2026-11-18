package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 中央 observation 收口：预填文本与失败结果都必须只走一次预算。
 */
public class ToolExecutionPipelineObservationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void prefilledObservationStillPassesThroughBudgetExactlyOnce() throws Exception {
        Path workspace = folder.newFolder("prefilled").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(workspace.toString()).build();
        String prefilled = "x".repeat(40_000);

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context,
                "bash",
                ToolResultPayload.builder()
                        .llmObservation(prefilled)
                        .llmData(Map.of("ok", Boolean.TRUE, "stdout", "small"))
                        .build());

        Assert.assertTrue(normalized.getLlmObservation().contains("<persisted-output>"));
        Assert.assertTrue(normalized.getToolResult().contains("\"stdout\":\"small\""));
        try (Stream<Path> files = Files.list(workspace.resolve(".cute/observations"))) {
            Assert.assertEquals(1, files.count());
        }
    }

    @Test
    public void stateAwareFailedResultKeepsCompactObservationAndFullToolResult() {
        AgentContext context = AgentContext.builder().build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "bash");
        data.put("ok", Boolean.FALSE);
        data.put("stdout", "partial");
        data.put("stderr", "bad command");
        data.put("exit_code", 2);
        data.put("duration_ms", 900);

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context,
                "bash",
                ToolResultPayload.builder().failed(Boolean.TRUE).llmData(data).build());

        Assert.assertTrue(normalized.getLlmObservation().contains("bad command"));
        Assert.assertTrue(normalized.getLlmObservation().contains("exit_code: 2"));
        Assert.assertFalse(normalized.getLlmObservation().contains("duration_ms"));
        Assert.assertTrue(normalized.getToolResult().contains("\"duration_ms\":900"));
    }

    @Test
    public void unregisteredFailureKeepsLegacyJsonFallback() {
        AgentContext context = AgentContext.builder().build();
        Map<String, Object> detail = Map.of("type", "tool_error", "message", "boom");

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context,
                "some_mcp_tool",
                ToolResultPayload.builder().failed(Boolean.TRUE).llmData(detail).build());

        Assert.assertTrue(normalized.getLlmObservation().contains("\"tool_ok\":false"));
        Assert.assertTrue(normalized.getLlmObservation().contains("boom"));
    }

    @Test
    public void phaseTwoFailedToolKeepsCompactObservationAndFullToolResult() {
        AgentContext context = AgentContext.builder().build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", Boolean.FALSE);
        data.put("message", "parse failed");
        data.put("data", "partial body");
        data.put("file_size", 4096);

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context,
                "pdf_reader",
                ToolResultPayload.builder().failed(Boolean.TRUE).llmData(data).build());

        Assert.assertTrue(normalized.getLlmObservation().contains("parse failed"));
        Assert.assertTrue(normalized.getLlmObservation().contains("partial body"));
        Assert.assertFalse(normalized.getLlmObservation().contains("file_size"));
        Assert.assertTrue(normalized.getToolResult().contains("\"file_size\":4096"));
    }
}
