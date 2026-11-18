package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.Map;

public class ToolExecutionTruthfulnessTest {

    @Test
    public void okFalseInToolResultIsHardFailureEvenWhenStatusIsSuccess() {
        ToolResultPayload payload = ToolResultPayload.fromData(Map.of(
                "ok", Boolean.FALSE,
                "message", "soft failure"
        ));

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                null, "bash", payload);

        Assert.assertEquals(Boolean.TRUE, normalized.getFailed());
        Assert.assertNotNull(normalized.getErrorMsg());
    }

    @Test
    public void failureTextInToolResultIsHardFailureEvenWhenStatusIsSuccess() {
        ToolResultPayload payload = ToolResultPayload.fromData(Map.of(
                "ok", Boolean.TRUE,
                "message", "code_execution 403 forbidden"
        ));

        ToolResultPayload normalized = ToolExecutionPipeline.normalizeToolResultPayload(
                null, "code_execution", payload);

        Assert.assertEquals(Boolean.TRUE, normalized.getFailed());
        Assert.assertNotNull(normalized.getErrorMsg());
    }
}
