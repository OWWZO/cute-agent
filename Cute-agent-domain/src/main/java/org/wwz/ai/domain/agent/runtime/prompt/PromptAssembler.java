package org.wwz.ai.domain.agent.runtime.prompt;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 普通 Agent、协调者和 Worker 的统一提示词装配入口。
 *
 * <p>稳定前缀应只在会话首轮生成一次，后续由会话快照复用。动态上下文
 * 不在这里拼装。</p>
 */
public final class PromptAssembler {

    public static final String VERSION_PREFIX = "prompt-v1";

    private PromptAssembler() {
    }

    public static PromptBundle assemble(PromptAssemblyContext context) {
        PromptAssemblyContext effective = context == null
                ? PromptAssemblyContext.builder().build()
                : context;
        List<String> sections = new ArrayList<>();
        List<String> names = new ArrayList<>();

        if (effective.isCoordinator()) {
            add(sections, names, "core", AgentPrompt.AGENT_CORE_RULES);
            add(sections, names, "coordinator", PlanSolvePrompt.ORCHESTRATION);
            add(sections, names, "workerCatalog", effective.getWorkerCatalog());
        } else {
            add(sections, names, "core", AgentPrompt.AGENT_CORE_RULES);
            add(sections, names, "agentRole", effective.getAgentDirective());
        }

        if (effective.isIncludeAgentDispatchGuidance()) {
            add(sections, names, "agentDispatch", AgentPrompt.AGENT_DISPATCH_GUIDANCE);
        }
        if (effective.isIncludeBackgroundTaskGuidance()) {
            add(sections, names, "backgroundTasks", AgentPrompt.BACKGROUND_TASK_GUIDANCE);
        }
        if (effective.isIncludeParallelToolGuidance()) {
            add(sections, names, "parallelTools", AgentPrompt.PARALLEL_TOOL_GUIDANCE);
        }
        if (effective.isIncludeMemoryGuidance()) {
            add(sections, names, "memory", AgentPrompt.MEMORY_GUIDANCE);
        }
        if (effective.isIncludeWorkspaceGuidance()) {
            add(sections, names, "workspace", AgentPrompt.WORKSPACE_GUIDANCE);
        }
        add(sections, names, "skillIndex", effective.getSkillIndex());
        add(sections, names, "taskInstructions", effective.getTaskInstructions());

        if (effective.isIncludeUserFacingReplyContract()) {
            sections.add(AgentPrompt.USER_FACING_REPLY_CONTRACT);
            names.add("userFacingReplyContract");
        }
        add(sections, names, "benchmarkContract", effective.getBenchmarkContract());

        String systemPrompt = join(sections);
        if (effective.isIncludeOpenUiProtocol()) {
            systemPrompt = AgentPrompt.ensureOpenUiProtocol(systemPrompt);
            names.add("openUiProtocol");
        }
        String hash = sha256(systemPrompt);
        String version = VERSION_PREFIX + ":" + hash.substring(0, 12);
        return new PromptBundle(systemPrompt, version, hash, names);
    }

    public static PromptBundle forAgent(String agentKey, String agentDirective) {
        return assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.MAIN)
                .agentKey(agentKey)
                .agentDirective(agentDirective)
                .build());
    }

    public static PromptBundle forWorker(String agentKey, String agentDirective) {
        return assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.WORKER)
                .agentKey(agentKey)
                .agentDirective(agentDirective)
                .includeAgentDispatchGuidance(true)
                .build());
    }

    public static PromptBundle forCoordinator(String workerCatalog) {
        return assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.COORDINATOR)
                .workerCatalog(workerCatalog)
                .includeAgentDispatchGuidance(true)
                .build());
    }

    /**
     * 按当前运行上下文和实际工具能力构造装配输入。
     *
     * <p>该 builder 只描述会话级能力，不读取 query、日期、历史或工具结果。</p>
     */
    public static PromptAssemblyContext.PromptAssemblyContextBuilder runtimeContext(
            AgentContext context,
            AgentExecutionRole role,
            String agentKey,
            String agentDirective) {
        ToolCollection tools = context == null ? null : context.getToolCollection();
        boolean agentDispatchAvailable = tools != null
                && tools.getTool(AgentDispatchTool.NAME) != null;
        boolean workspaceAvailable = tools != null
                && tools.getToolMap() != null
                && tools.getToolMap().keySet().stream().anyMatch(name -> name.startsWith("workspace_"));
        boolean multipleTools = tools != null
                && tools.getToolMap() != null
                && tools.getToolMap().size() > 1;
        return PromptAssemblyContext.builder()
                .role(role)
                .agentKey(agentKey)
                .agentDirective(StringUtils.defaultIfBlank(
                        agentDirective, AgentPrompt.DEFAULT_AGENT_DIRECTIVE))
                // memory/skill 细则由 BaseAgent 的运行时能力块统一追加。
                .includeMemoryGuidance(false)
                .includeAgentDispatchGuidance(agentDispatchAvailable)
                .includeBackgroundTaskGuidance(agentDispatchAvailable)
                .includeParallelToolGuidance(multipleTools)
                .includeWorkspaceGuidance(workspaceAvailable)
                .includeUserFacingReplyContract(true);
    }

    /**
     * Prepare 阶段一次性装配最终 system 提示词，随后由 Agent 构造时直接冻结。
     */
    public static PromptBundle assembleForContext(AgentContext context,
                                                  AgentExecutionRole role,
                                                  String agentKey,
                                                  String agentDirective,
                                                  String workerCatalog) {
        PromptAssemblyContext.PromptAssemblyContextBuilder builder =
                runtimeContext(context, role, agentKey, agentDirective)
                        .workerCatalog(workerCatalog);
        if (role == AgentExecutionRole.COORDINATOR) {
            builder.includeAgentDispatchGuidance(true);
        }
        if (role == AgentExecutionRole.MAIN || role == AgentExecutionRole.COORDINATOR) {
            builder.includeOpenUiProtocol(true);
        }
        return assemble(builder.build());
    }

    /**
     * 协调者目录只暴露调度所需的 key 和适用场景，不携带完整领域提示词。
     */
    public static String formatWorkerCatalog(Collection<SubAgentDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return "";
        }
        List<SubAgentDefinition> ordered = definitions.stream()
                .filter(java.util.Objects::nonNull)
                .filter(definition -> StringUtils.isNotBlank(definition.getAgentType()))
                .sorted(Comparator.comparing(SubAgentDefinition::getAgentType))
                .toList();
        if (ordered.isEmpty()) {
            return "";
        }
        StringBuilder catalog = new StringBuilder("# 可用 Agent 目录\n")
                .append("- 所有已启用 Agent 都可被派发；按任务选择最合适的一个，不要为同一任务重复派发。\n");
        for (SubAgentDefinition definition : ordered) {
            catalog.append("- `")
                    .append(definition.getAgentType().trim())
                    .append("`: ")
                    .append(StringUtils.defaultIfBlank(
                            definition.getWhenToUse(), "按名称和当前任务判断适用性"))
                    .append('\n');
        }
        return catalog.toString();
    }

    private static void add(List<String> sections, List<String> names, String name, String section) {
        if (StringUtils.isBlank(section)) {
            return;
        }
        sections.add(section.trim());
        names.add(name);
    }

    private static String join(List<String> sections) {
        if (sections.isEmpty()) {
            return "";
        }
        return String.join("\n\n", sections).trim() + "\n";
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
