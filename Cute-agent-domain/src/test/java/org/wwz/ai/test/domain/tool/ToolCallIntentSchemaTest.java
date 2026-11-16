package org.wwz.ai.test.domain.tool;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCallIntentSchema;
import org.wwz.ai.domain.agent.runtime.tool.browser.AgentBrowserTool;
import org.wwz.ai.domain.agent.runtime.tool.browser.BrowserTool;
import org.wwz.ai.domain.agent.runtime.tool.cli.HostCliTool;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeTool;
import org.wwz.ai.domain.agent.runtime.tool.common.CodeExecutionTool;
import org.wwz.ai.domain.agent.runtime.tool.common.shell.BashTool;

import java.util.List;
import java.util.Map;

public class ToolCallIntentSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    public void codeToolsExposeRequiredIntent() {
        List<BaseTool> tools = List.of(
                new BashTool(null, null),
                new CodeExecutionTool(),
                new CodeModeTool(null),
                new BrowserTool(),
                new AgentBrowserTool(),
                new HostCliTool(List.of("twitter"))
        );

        for (BaseTool tool : tools) {
            Map<String, Object> params = tool.toParams();
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");
            Map<String, Object> intent = (Map<String, Object>) properties.get(ToolCallIntentSchema.FIELD);

            Assert.assertNotNull(tool.getName(), intent);
            Assert.assertEquals(tool.getName(), "string", intent.get("type"));
            Assert.assertEquals(tool.getName(), 1, intent.get("minLength"));
            Assert.assertEquals(tool.getName(), 160, intent.get("maxLength"));
            Assert.assertTrue(tool.getName(),
                    ((List<?>) params.get("required")).contains(ToolCallIntentSchema.FIELD));
        }
    }

    @Test
    public void toolDescriptionsExplainIntent() {
        Assert.assertTrue(new BashTool(null, null).getDescription()
                .contains(ToolCallIntentSchema.FIELD));
        Assert.assertTrue(new CodeExecutionTool().getDescription()
                .contains(ToolCallIntentSchema.FIELD));
        Assert.assertTrue(new CodeModeTool(null).getDescription()
                .contains(ToolCallIntentSchema.FIELD));
        Assert.assertTrue(new BrowserTool().getDescription()
                .contains(ToolCallIntentSchema.FIELD));
        Assert.assertTrue(new AgentBrowserTool().getDescription()
                .contains(ToolCallIntentSchema.FIELD));
        Assert.assertTrue(new HostCliTool(List.of("twitter")).getDescription()
                .contains(ToolCallIntentSchema.FIELD));
    }
}
