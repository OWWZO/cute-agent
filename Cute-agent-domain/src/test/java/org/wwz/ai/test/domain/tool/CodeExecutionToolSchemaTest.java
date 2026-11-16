package org.wwz.ai.test.domain.tool;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.CodeExecutionTool;

import java.util.List;
import java.util.Map;

/**
 * intent 必填；source / workspaceFile 仍是二选一关系，schema 不能强制 source。
 */
public class CodeExecutionToolSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    public void schemaRequiresIntentButNotSource() {
        Map<String, Object> params = new CodeExecutionTool().toParams();
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");

        Assert.assertTrue(properties.containsKey("intent"));
        Assert.assertTrue(properties.containsKey("source"));
        Assert.assertTrue(properties.containsKey("workspaceFile"));
        Assert.assertEquals(List.of("intent"), params.get("required"));
    }

    @Test
    public void executionRequiresSourceOrWorkspaceFile() {
        ToolResultPayload payload = (ToolResultPayload) new CodeExecutionTool().execute(Map.of(), null);

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getToolResult().contains("source 与 workspaceFile 必须提供一个"));
    }

    @Test
    public void descriptionRequiresOnePassDataPreparation() {
        String description = new CodeExecutionTool().getDescription();

        Assert.assertTrue(description.contains("输入数据确定后"));
        Assert.assertTrue(description.contains("允许必要迭代"));
        Assert.assertTrue(description.contains("print(\"ok\")"));
        Assert.assertTrue(description.contains("intent"));
    }
}
