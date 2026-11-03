package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentToolFilter;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.Map;
import java.util.Set;

/**
 * allowedTools 的契约：null / "*" 表示全部允许，显式空集合表示不允许任何工具。
 * 空集合曾被当成「全部允许」，是一个静默提权口子。
 */
public class SubAgentAllowlistContractTest {

    @Test
    public void emptyAllowlistMeansNoTools() {
        SubAgentDefinition definition = SubAgentDefinition.builder()
                .agentType("no-tools")
                .allowedTools(Set.of())
                .build();

        Assert.assertFalse(definition.allowsAllTools());

        ToolCollection child = SubAgentToolFilter.filter(parentTools(), definition);
        Assert.assertTrue("空 allowlist 不得继承父级本地工具", child.getToolMap().isEmpty());
        Assert.assertTrue("空 allowlist 不得继承父级 MCP 工具", child.getMcpToolMap().isEmpty());
    }

    @Test
    public void nullAndStarAllowlistsStillMeanAll() {
        Assert.assertTrue(SubAgentDefinition.builder().agentType("inherit").build().allowsAllTools());
        Assert.assertTrue(SubAgentDefinition.builder()
                .agentType("star")
                .allowedTools(Set.of("*"))
                .build()
                .allowsAllTools());
    }

    @Test
    public void explicitAllowlistOnlyKeepsListedTools() {
        SubAgentDefinition definition = SubAgentDefinition.builder()
                .agentType("read-only")
                .allowedTools(Set.of("workspace_read"))
                .build();

        ToolCollection child = SubAgentToolFilter.filter(parentTools(), definition);

        Assert.assertTrue(child.getToolMap().containsKey("workspace_read"));
        Assert.assertFalse("名单外的工具不得进入子会话", child.getToolMap().containsKey("workspace_write"));
    }

    private static ToolCollection parentTools() {
        ToolCollection tools = new ToolCollection();
        tools.addTool(new StubTool("workspace_read"));
        tools.addTool(new StubTool("workspace_write"));
        return tools;
    }

    private static final class StubTool implements BaseTool {
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
        public Map<String, Object> toParams() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return null;
        }
    }
}
