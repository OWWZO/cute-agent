package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentToolFilter;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.common.canvas.CanvasToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.ChartGeneratorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillViewTool;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillsSearchTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 定义表里的工具名必须与代码里注册的工具名一致。
 * <p>
 * 不一致的后果是静默的：{@link SubAgentToolFilter} 按名字做白名单，名字对不上就等同于
 * 「这个类型不允许该工具」，子 Agent 会少拿能力，但没有任何报错。
 * <p>
 * 2026-10-27 实测：定义表 38 个工具名里有 2 个是坏的（{@code skill_tool}、
 * {@code get_html_canvas_guide}），已由 {@code 20261027_fix_subagent_tool_names.sql} 修正。
 * 本测试锁住修正结果，防止再漂移。
 */
public class SubAgentDefinitionToolNameContractTest {

    /**
     * 各类型在 {@code ai_agent_sub_agent_definition.allowed_tools_json} 里的工具名。
     * 与库同步，改库时必须同步改这里。
     */
    private static final Map<String, Set<String>> DEFINITION_TOOL_NAMES = Map.of(
            "report_agent", Set.of(
                    "workspace_read", "workspace_list", "workspace_glob", "workspace_grep",
                    "workspace_write", "workspace_edit", "skill_view", "skills_search",
                    "TodoWrite", "chart_generator", "html_processor", "canvas_publish"),
            "deep-search", Set.of(
                    "workspace_read", "workspace_list", "workspace_glob", "workspace_grep",
                    "workspace_write", "workspace_edit", "deep_search", "WebFetch", "WebSearch",
                    "skill_view", "skills_search"),
            "code-executor", Set.of(
                    "workspace_read", "workspace_write", "workspace_edit", "workspace_list",
                    "workspace_glob", "workspace_grep", "code_execution", "TodoWrite",
                    "skill_view", "skills_search")
    );

    @Test
    public void everyDefinitionToolNameMustExistInCode() {
        Set<String> registered = toolPool().getToolMap().keySet();

        DEFINITION_TOOL_NAMES.forEach((type, names) -> names.forEach(name ->
                Assert.assertTrue(
                        "定义表 " + type + " 里的工具名在代码中不存在：" + name,
                        registered.contains(name))));
    }

    @Test
    public void skillToolsMustReachTheSubAgent() {
        ToolCollection child = SubAgentToolFilter.filter(toolPool(), definition("report_agent"));

        // 曾经写成 skill_tool（代码里没有这个名字），导致所有类型静默丢失 skill 能力
        Assert.assertTrue("skill_view 未到达子 Agent",
                child.getToolMap().containsKey(SkillViewTool.NAME));
        Assert.assertTrue("skills_search 未到达子 Agent",
                child.getToolMap().containsKey(SkillsSearchTool.NAME));
    }

    @Test
    public void canvasToolsMustReachTheReportAgent() {
        ToolCollection child = SubAgentToolFilter.filter(toolPool(), definition("report_agent"));

        Assert.assertTrue(child.getToolMap().containsKey(CanvasToolNames.CANVAS_PUBLISH));
        Assert.assertTrue(child.getToolMap().containsKey(ChartGeneratorTool.TOOL_NAME));
    }

    @Test
    public void codeExecutorMustNotSeeSearchTools() {
        ToolCollection child = SubAgentToolFilter.filter(toolPool(), definition("code-executor"));

        Assert.assertTrue(child.getToolMap().containsKey("code_execution"));
        Assert.assertFalse("定义外工具不得进入子会话",
                child.getToolMap().containsKey("deep_search"));
    }

    private static SubAgentDefinition definition(String agentType) {
        return SubAgentDefinition.builder()
                .agentType(agentType)
                .allowedTools(DEFINITION_TOOL_NAMES.get(agentType))
                .toolPolicyMode(SubAgentDefinition.TOOL_POLICY_INHERIT)
                .build();
    }

    /** 与 AgentToolCollectionFactory 实际会注册的工具名对齐的最小池。 */
    private static ToolCollection toolPool() {
        ToolCollection pool = new ToolCollection();
        Map<String, BaseTool> tools = new LinkedHashMap<>();
        tools.put(SkillViewTool.NAME, new StubTool(SkillViewTool.NAME));
        tools.put(SkillsSearchTool.NAME, new StubTool(SkillsSearchTool.NAME));
        tools.put(CanvasToolNames.CANVAS_PUBLISH, new StubTool(CanvasToolNames.CANVAS_PUBLISH));
        tools.put(ChartGeneratorTool.TOOL_NAME, new StubTool(ChartGeneratorTool.TOOL_NAME));
        tools.put("workspace_read", new StubTool("workspace_read"));
        tools.put("workspace_list", new StubTool("workspace_list"));
        tools.put("workspace_glob", new StubTool("workspace_glob"));
        tools.put("workspace_grep", new StubTool("workspace_grep"));
        tools.put("workspace_write", new StubTool("workspace_write"));
        tools.put("workspace_edit", new StubTool("workspace_edit"));
        tools.put("code_execution", new StubTool("code_execution"));
        tools.put("deep_search", new StubTool("deep_search"));
        tools.put("TodoWrite", new StubTool("TodoWrite"));
        tools.put("html_processor", new StubTool("html_processor"));
        tools.put("WebFetch", new StubTool("WebFetch"));
        tools.put("WebSearch", new StubTool("WebSearch"));
        tools.values().forEach(pool::addTool);
        return pool;
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
        public Object execute(Object input, org.wwz.ai.domain.agent.runtime.agent.AgentContext context) {
            return null;
        }
    }
}
