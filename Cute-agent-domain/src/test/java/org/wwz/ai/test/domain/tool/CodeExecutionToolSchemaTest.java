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

    @Test
    @SuppressWarnings("unchecked")
    public void descriptionAndSchemaExplainJupyterSessionState() {
        CodeExecutionTool tool = new CodeExecutionTool();
        String description = tool.getDescription();

        Assert.assertTrue(description.contains("Jupyter"));
        Assert.assertTrue(description.contains("变量"));
        Assert.assertTrue(description.contains("保留"));
        Assert.assertTrue(description.contains("subprocess"));
        Assert.assertTrue(description.contains("Settings already configured"));

        Map<String, Object> params = tool.toParams();
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        Map<String, Object> source = (Map<String, Object>) properties.get("source");
        String sourceDescription = String.valueOf(source.get("description"));
        Assert.assertTrue(sourceDescription.contains("Jupyter"));
        Assert.assertTrue(sourceDescription.contains("subprocess"));
    }
}
