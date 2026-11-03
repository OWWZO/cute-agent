package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.ToolCallTool;

import java.util.List;
import java.util.Map;

/**
 * ToolCall 的自由对象参数 schema 必须显式允许额外字段。
 */
public class ToolCallToolSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    public void argumentsSchemaAcceptsAdditionalProperties() {
        Map<String, Object> parameters = new ToolCallTool().toParams();
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        Map<String, Object> arguments = (Map<String, Object>) properties.get("arguments");

        Assert.assertEquals("object", arguments.get("type"));
        Assert.assertEquals(Boolean.TRUE, arguments.get("additionalProperties"));
        Assert.assertTrue(((Map<String, Object>) arguments.get("properties")).isEmpty());
        Assert.assertEquals(List.of(), arguments.get("required"));
    }
}
