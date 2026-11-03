package org.wwz.ai.domain.agent.memory.ltm;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpToolInfo;

/**
 * 策展白名单必须与「父会话实际工具」的判定口径一致：MCP 工具也算父工具。
 */
public class LtmForkParityWhitelistTest {

    @Test
    public void mcpExposedCuratorToolIsIncludedInWhitelist() {
        ToolCollection parent = new ToolCollection();
        parent.addMcpTool(McpToolInfo.builder().name("bash").build());
        parent.addMcpTool(McpToolInfo.builder().name("unrelated_tool").build());

        Assert.assertTrue(LtmForkParity.forReview("sys", parent, java.util.List.of()).hasParentTools());
        Assert.assertTrue(LtmForkParity.resolveCuratorWhitelist(parent).contains("bash"));
    }

    @Test
    public void localToolsStillResolveAsBefore() {
        ToolCollection parent = new ToolCollection();
        parent.addTool(new StubTool("workspace_read"));

        Assert.assertTrue(LtmForkParity.resolveCuratorWhitelist(parent).contains("workspace_read"));
    }

    private static final class StubTool implements org.wwz.ai.domain.agent.runtime.tool.BaseTool {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name;
        }

        @Override
        public java.util.Map<String, Object> toParams() {
            return java.util.Map.of();
        }

        @Override
        public Object execute(Object input, org.wwz.ai.domain.agent.runtime.agent.AgentContext context) {
            return null;
        }
    }
}
