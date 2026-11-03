package org.wwz.ai.domain.agent.runtime.planmode;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.McpToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan Mode 工具门禁（执行期拒绝写操作与有副作用的命令；只做拦截，不改发送给模型的 tools[]）。
 * <p>
 * 注意：这里不再区分"外部检索"与"本地只读"——检索类工具在 plan 期正常可用。
 * 门禁只在执行期生效，模型能看见全部工具，因此不需要"先隐藏再拒绝"的两段式设计。
 */
public final class PlanModeToolPolicy {

    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            TaskToolNames.ENTER_PLAN_MODE,
            TaskToolNames.EXIT_PLAN_MODE,
            TaskToolNames.TASK_CREATE,
            TaskToolNames.TASK_GET,
            TaskToolNames.TASK_UPDATE,
            TaskToolNames.TASK_LIST,
            TaskToolNames.TODO_WRITE,
            TaskToolNames.TASK_STOP,
            TaskToolNames.TASK_OUTPUT,
            TaskToolNames.SEND_MESSAGE,
            "AskUserQuestion",
            AgentDispatchTool.NAME,
            "workspace_read",
            "workspace_list",
            "workspace_glob",
            "workspace_grep",
            "skill_view",
            "skills_search",
            McpToolNames.TOOL_SEARCH,
            McpToolNames.TOOL_DESCRIBE
    );

    /** 执行本机命令 / 操作浏览器：不是"检索"，而是有副作用的外部动作，plan 期仍拦。 */
    private static final Set<String> SIDE_EFFECT_TOOLS = Set.of(
            "host_cli",
            "browser",
            "agent_browser",
            "opencli",
            "bash",
            "code_mode"
    );

    private static final Set<String> MUTATING = Set.of(
            "workspace_write",
            "workspace_edit",
            "code_interpreter",
            "document_generate",
            "slides_generate",
            "excel_generator",
            "checklist_generate",
            "template_filler",
            "canvas_publish",
            "image_generation",
            "data_analysis",
            "multimodalagent_tool"
    );

    private PlanModeToolPolicy() {
    }

    /**
     * @return null 表示放行；非 null 为拒绝原因（给模型看）
     */
    public static String denyReason(AgentContext context, String toolName, Object args) {
        if (context == null || context.isSubAgent()) {
            return null;
        }
        return denyReason(context.getPlanModeState(), toolName, args);
    }

    /**
     * @return null 表示放行；非 null 为拒绝原因（给模型看）
     */
    public static String denyReason(PlanModeState state, String toolName, Object args) {
        if (state == null || !state.isPlanMode() || toolName == null) {
            return null;
        }
        String name = toolName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (McpToolNames.TOOL_CALL.equals(name)) {
            // ToolCall 本身只是转发器：真正是否有副作用取决于 arguments.name 指向的工具。
            // 必须递归同一门禁，否则任何写操作都能借 ToolCall 绕过 plan 期保护。
            return denyToolCall(state, args);
        }
        if (ALWAYS_ALLOWED.contains(name)) {
            // 主 Agent 在 plan 期允许这些工具。子 Agent 不经过本策略（context.isSubAgent() 直接放行），
            // 其工具池由 SubAgentFilter 管理，且不会剥离 workspace_write/workspace_edit。
            return null;
        }
        if (SIDE_EFFECT_TOOLS.contains(name)) {
            return "Plan mode: 禁止调用会执行本机命令或操作浏览器的工具「" + name + "」。"
                    + " 请只使用只读工具与检索工具；退出 plan mode 并获用户批准后再执行。";
        }
        if ("workspace_write".equals(name) || "workspace_edit".equals(name)) {
            String path = extractPath(args);
            if (StringUtils.isNotBlank(path) && isPlanRelativePath(path)) {
                return null;
            }
            return "Plan mode: 只能写入会话计划文件（.cute/plan.md），禁止修改其它文件。"
                    + " 请把计划写到 .cute/plan.md，或完成后调用 ExitPlanMode 请求用户批准。";
        }
        if (MUTATING.contains(name)) {
            return "Plan mode: 禁止调用会修改系统状态的工具「" + name + "」。"
                    + " 请只做只读探索、AskUserQuestion、写计划文件，或 ExitPlanMode。";
        }
        // MCP / 未知工具：默认允许只读类；名字含 write/edit/run 则拦
        if (lower.contains("write") || lower.contains("edit") || lower.contains("delete")
                || lower.contains("exec") || lower.contains("run_command")) {
            return "Plan mode: 工具「" + name + "」可能修改状态，已禁止。请退出 plan mode 并获用户批准后再用。";
        }
        return null;
    }

    /**
     * 解析 ToolCall 的目标工具并按同一策略复检；目标名缺失或指向桥工具时拒绝，
     * 因为无法证明该调用只读。
     */
    private static String denyToolCall(PlanModeState state, Object args) {
        Map<String, Object> call = ToolArgs.map(args);
        String target = ToolArgs.str(call.get("name"));
        if (StringUtils.isBlank(target)) {
            return "Plan mode: ToolCall 必须显式指定目标工具名（name），无法确认是否只读，已拒绝。";
        }
        if (McpToolNames.isBridge(target)) {
            return "Plan mode: ToolCall 不能转而调用桥工具「" + target + "」。";
        }
        return denyReason(state, target, ToolArgs.map(call.get("arguments")));
    }

    private static boolean isPlanRelativePath(String path) {
        String normalized = path.replace('\\', '/').trim();
        return normalized.equals(PlanArtifactStore.RELATIVE_PLAN_PATH)
                || normalized.endsWith("/" + PlanArtifactStore.RELATIVE_PLAN_PATH)
                || normalized.endsWith(".cute/plan.md");
    }

    @SuppressWarnings("unchecked")
    private static String extractPath(Object args) {
        if (!(args instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        Object path = map.get("path");
        if (path == null) {
            path = map.get("file_path");
        }
        if (path == null) {
            path = map.get("filePath");
        }
        return path == null ? null : String.valueOf(path);
    }
}
