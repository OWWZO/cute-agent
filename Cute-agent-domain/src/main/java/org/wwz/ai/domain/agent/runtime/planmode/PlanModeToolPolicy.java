package org.wwz.ai.domain.agent.runtime.planmode;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.McpToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;

import java.util.Arrays;
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

    /**
     * 未知工具名里出现这些 token 即按「可能改状态」拒绝。
     * 只做整词匹配，避免 read/edit 这类子串误伤 editor 等只读命名。
     */
    private static final Set<String> MUTATING_TOKENS = Set.of(
            "write", "edit", "delete", "remove", "create", "update", "move", "copy", "upload",
            "exec", "execute", "run", "command", "bash", "shell", "powershell", "patch", "install"
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
        // MCP / 未知工具：默认允许只读类；把 camelCase / 分隔符归一后按整词拦，
        // 避免 runCommand 这类写法漏网，也避免 read_editor 这类只读命名被误伤。
        if (hasMutatingToken(name)) {
            return "Plan mode: 工具「" + name + "」可能修改状态，已禁止。请退出 plan mode 并获用户批准后再用。";
        }
        return null;
    }

    private static boolean hasMutatingToken(String toolName) {
        String normalized = toolName.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(normalized.split("_"))
                .filter(token -> !token.isBlank())
                .anyMatch(MUTATING_TOKENS::contains);
    }

    /**
     * 只放行工作区根下精确的 {@code .cute/plan.md}。
     * <p>不接受 {@code foo.cute/plan.md}、{@code sub/.cute/plan.md}、绝对路径或含 {@code ..}
     * 的写法——旧实现用 {@code endsWith} 判断，等于允许在任意子目录写同名文件。</p>
     */
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
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.startsWith("~")) {
            return false;
        }
        if (normalized.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return PlanArtifactStore.RELATIVE_PLAN_PATH.equals(normalized);
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
