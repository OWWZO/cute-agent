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
 * allowedTools 的契约：null / "*" 表示全部业务工具，显式空集合表示不允许业务工具。
 * Agent / TaskStop / SendMessage 是运行时管理契约，不参与 allowlist。
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
        Assert.assertFalse("空 allowlist 不得继承父级本地工具",
                child.getToolMap().containsKey("workspace_read"));
        Assert.assertFalse("空 allowlist 不得继承父级本地工具",
                child.getToolMap().containsKey("workspace_write"));
        Assert.assertTrue("空 allowlist 不得继承父级 MCP 工具", child.getMcpToolMap().isEmpty());
        Assert.assertTrue("管理工具不受 allowlist 控制", child.getToolMap().containsKey("Agent"));
        Assert.assertTrue("管理工具不受 allowlist 控制", child.getToolMap().containsKey("TaskStop"));
        Assert.assertTrue("管理工具不受 allowlist 控制", child.getToolMap().containsKey("SendMessage"));
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

    @Test
    public void dataAgentNeverInheritsManagementTools() {
        SubAgentDefinition definition = SubAgentDefinition.builder()
                .agentType("dataAgent")
                .build();
        ToolCollection parent = parentTools();
        parent.addTool(new StubTool("Agent"));
        parent.addTool(new StubTool("TaskStop"));
        parent.addTool(new StubTool("SendMessage"));

        ToolCollection child = SubAgentToolFilter.filter(parent, definition);

        Assert.assertFalse(child.getToolMap().containsKey("Agent"));
        Assert.assertFalse(child.getToolMap().containsKey("TaskStop"));
        Assert.assertFalse(child.getToolMap().containsKey("SendMessage"));
        Assert.assertTrue(child.getToolMap().containsKey("workspace_read"));
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
