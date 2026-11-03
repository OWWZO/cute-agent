package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;

/**
 * 工具入参归一必须稳定为 JSON 对象文本。
 */
public class ToolCallJsonTest {

    @Test
    public void nullLiteralNormalizesToEmptyObject() {
        Assert.assertEquals("{}", ToolCallJson.normalize("null"));
    }

    @Test
    public void scalarAndArrayNormalizeToEmptyObject() {
        Assert.assertEquals("{}", ToolCallJson.normalize("123"));
        Assert.assertEquals("{}", ToolCallJson.normalize("[1,2]"));
        Assert.assertEquals("{}", ToolCallJson.normalize("\"abc\""));
    }

    @Test
    public void objectPayloadKeepsFullNumericPrecision() {
        String normalized = ToolCallJson.normalize("{\"lat\": 31.230416123456789}");
        Assert.assertEquals("{\"lat\":31.230416123456789}", normalized);
    }
}
