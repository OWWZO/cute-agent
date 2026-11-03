package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.nio.file.Path;
import java.util.Map;

/**
 * 无 llmData / 无投影器的结果也必须经过 observation 预算。
 */
public class ToolExecutionPipelineObservationBudgetTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void toolResultOnlyPayloadIsBudgeted() throws Exception {
        AgentContext context = contextWithWorkspace();
        ToolResultPayload payload = ToolResultPayload.builder()
                .toolResult("x".repeat(40_000))
                .failed(Boolean.FALSE)
                .build();

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context, "legacy_tool", payload);

        Assert.assertTrue(normalized.getLlmObservation().contains("<persisted-output>"));
        Assert.assertTrue("信封只保留预览，不再带完整 40KB 内容",
                normalized.getLlmObservation().length() < 5_000);
    }

    @Test
    public void rawStringResultIsBudgeted() throws Exception {
        AgentContext context = contextWithWorkspace();

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context, "legacy_tool", "y".repeat(40_000));

        Assert.assertTrue(normalized.getLlmObservation().contains("<persisted-output>"));
    }

    @Test
    public void genericObjectResultIsBudgeted() throws Exception {
        AgentContext context = contextWithWorkspace();
        Map<String, String> result = Map.of("payload", "z".repeat(40_000));

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                context, "legacy_tool", result);

        Assert.assertTrue(normalized.getLlmObservation().contains("<persisted-output>"));
    }

    private AgentContext contextWithWorkspace() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        return AgentContext.builder().workspaceRoot(root.toString()).build();
    }
}
