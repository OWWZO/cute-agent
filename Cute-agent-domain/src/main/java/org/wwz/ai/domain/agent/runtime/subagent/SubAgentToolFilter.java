package org.wwz.ai.domain.agent.runtime.subagent;

import org.wwz.ai.domain.agent.memory.ltm.LtmMemoryGuard;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpToolInfo;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.SessionSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.McpToolNames;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.ToolCallTool;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.ToolDescribeTool;
import org.wwz.ai.domain.agent.runtime.tool.common.mcp.ToolSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AgentListTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.SendMessageTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskStopTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;
import org.wwz.ai.domain.agent.runtime.tool.deferred.DeferredToolCatalog;
import org.wwz.ai.domain.agent.runtime.tool.deferred.DeferredToolEntry;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 子 Agent 工具池过滤。
 *
 * <p>通用子 Agent 继承父 Agent 的完整有效视图；领域子 Agent 在 custom
 * 模式下按自己的 allowedTools/deferredTools 重新划分 eager 与 catalog。</p>
 */
public final class SubAgentToolFilter {

    private SubAgentToolFilter() {
    }

    public static ToolCollection filter(ToolCollection parentTools, SubAgentDefinition definition) {
        ToolCollection child = new ToolCollection();
        if (parentTools == null || definition == null) {
            return child;
        }
        child.setMcpToolExecutor(parentTools.getMcpToolExecutor());
        child.restoreTaskScopedState(parentTools.snapshotTaskScopedState());

        Set<String> disallowed = buildDisallowed(definition);
        if (definition.usesCustomToolPolicy()) {
            copyCustomView(parentTools, child, definition, disallowed);
        } else {
            copyInheritedView(parentTools, child, definition, disallowed);
        }
        alignBridgeTools(child, parentTools, disallowed);
        attachManagementTools(child, definition);
        return child;
    }

    private static Set<String> buildDisallowed(SubAgentDefinition definition) {
        Set<String> disallowed = new HashSet<>();
        disallowed.add(TaskToolNames.ENTER_PLAN_MODE);
        disallowed.add(TaskToolNames.EXIT_PLAN_MODE);
        disallowed.add(org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool.NAME);
        disallowed.add(org.wwz.ai.domain.agent.runtime.tool.common.planmode.RequestDesktopControlTool.NAME);
        disallowed.addAll(LtmMemoryGuard.MEMORY_WRITE_TOOLS);
        disallowed.add(SessionSearchTool.TOOL_NAME);
        if ("dataAgent".equals(definition.getAgentType())) {
            // dataAgent 不参与派发树；即使它继承的是父级全量工具视图，
            // 也不能把父 Agent 已装配的管理工具带进来。
            disallowed.add(AgentDispatchTool.NAME);
            disallowed.add(TaskToolNames.TASK_STOP);
            disallowed.add(TaskToolNames.SEND_MESSAGE);
        }
        if (definition.getDisallowedTools() != null) {
            disallowed.addAll(definition.getDisallowedTools());
        }
        return disallowed;
    }

    /**
     * 管理工具是 Agent 的运行契约，不属于业务 allowedTools。
     * 除 dataAgent 外，每个 Agent 都必须能创建、指导、停止自己的后代。
     */
    private static void attachManagementTools(ToolCollection child, SubAgentDefinition definition) {
        if ("dataAgent".equals(definition.getAgentType())) {
            return;
        }
        child.addTool(new AgentDispatchTool());
        child.addTool(new TaskStopTool());
        child.addTool(new AgentListTool());
        child.addTool(new SendMessageTool());
    }

    private static void copyInheritedView(ToolCollection parentTools,
                                          ToolCollection child,
                                          SubAgentDefinition definition,
                                          Set<String> disallowed) {
        boolean allowAll = definition.allowsAllTools();
        Set<String> allowed = definition.getAllowedTools() == null
                ? Set.of()
                : definition.getAllowedTools();

        if (parentTools.getToolMap() != null) {
            for (Map.Entry<String, BaseTool> entry : parentTools.getToolMap().entrySet()) {
                if (isAllowed(entry.getKey(), allowAll, allowed, disallowed)) {
                    child.addTool(entry.getValue());
                }
            }
        }
        if (parentTools.getMcpToolMap() != null) {
            for (McpToolInfo info : parentTools.getMcpToolMap().values()) {
                if (info != null && isAllowed(info.getName(), allowAll, allowed, disallowed)) {
                    child.addMcpTool(info);
                }
            }
        }

        DeferredToolCatalog parentCatalog = parentTools.getDeferredToolCatalog();
        if (parentCatalog != null && parentCatalog.size() > 0) {
            DeferredToolCatalog childCatalog = parentCatalog.filter(entry ->
                    isAllowed(entry.getName(), retainsParentCatalog(allowAll, allowed),
                            allowed, disallowed));
            if (childCatalog.size() > 0) {
                child.setDeferredToolCatalog(childCatalog);
            }
        }
    }

    private static void copyCustomView(ToolCollection parentTools,
                                       ToolCollection child,
                                       SubAgentDefinition definition,
                                       Set<String> disallowed) {
        boolean allowAll = definition.allowsAllTools();
        Set<String> allowed = definition.getAllowedTools() == null
                ? Set.of()
                : definition.getAllowedTools();
        Set<String> deferredNames = definition.getDeferredTools() == null
                ? Set.of()
                : definition.getDeferredTools();
        java.util.List<DeferredToolEntry> deferred = new java.util.ArrayList<>();

        if (parentTools.getToolMap() != null) {
            for (Map.Entry<String, BaseTool> entry : parentTools.getToolMap().entrySet()) {
                String name = entry.getKey();
                if (!isAllowed(name, allowAll, allowed, disallowed)) {
                    continue;
                }
                if (deferredNames.contains(name)) {
                    deferred.add(DeferredToolCatalog.localEntry(entry.getValue(), "cute"));
                } else {
                    child.addTool(entry.getValue());
                }
            }
        }
        if (parentTools.getMcpToolMap() != null) {
            for (McpToolInfo info : parentTools.getMcpToolMap().values()) {
                if (info == null || !isAllowed(info.getName(), allowAll, allowed, disallowed)) {
                    continue;
                }
                if (deferredNames.contains(info.getName())) {
                    deferred.add(DeferredToolCatalog.mcpEntry(info));
                } else {
                    child.addMcpTool(info);
                }
            }
        }

        DeferredToolCatalog parentCatalog = parentTools.getDeferredToolCatalog();
        if (parentCatalog != null) {
            boolean retainParentCatalog = retainsParentCatalog(allowAll, allowed);
            for (DeferredToolEntry entry : parentCatalog.listAll()) {
                if (!isAllowed(entry.getName(),
                        retainParentCatalog,
                        allowed,
                        disallowed)) {
                    continue;
                }
                if (retainParentCatalog || deferredNames.contains(entry.getName())) {
                    deferred.add(entry);
                } else if (entry.isLocal()) {
                    child.addTool(entry.getLocalTool());
                } else {
                    child.addMcpTool(entry.getMcpToolInfo());
                }
            }
        }
        if (!deferred.isEmpty()) {
            child.setDeferredToolCatalog(new DeferredToolCatalog(deferred));
        }
    }

    /**
     * 父级 deferred catalog 的保留判定。
     * <p>这是本类唯一一处「允许 ToolCall」同时改变工具可见范围的地方：ToolCall 是桥工具，
     * 被允许后本来就能触达 catalog 里的任意条目，若把 catalog 当未授权删掉，子 Agent 会
     * 出现「桥能调、但列表里看不到」的不一致；因此这里刻意让权限判断与延迟放置判断共用
     * 同一个标志。eager 工具仍严格按 allowedTools 过滤，不受该例外影响。</p>
     */
    private static boolean retainsParentCatalog(boolean allowAll, Set<String> allowed) {
        return allowAll || allowed.contains(McpToolNames.TOOL_CALL);
    }

    private static boolean isAllowed(String name,
                                     boolean allowAll,
                                     Set<String> allowed,
                                     Set<String> disallowed) {
        if (name == null || disallowed.contains(name)) {
            return false;
        }
        return allowAll || allowed.contains(name);
    }

    private static void alignBridgeTools(ToolCollection child,
                                         ToolCollection parentTools,
                                         Set<String> disallowed) {
        boolean keep = child.getDeferredToolCatalog() != null
                && child.getDeferredToolCatalog().size() > 0;
        for (String name : McpToolNames.BRIDGE_NAMES) {
            if (!keep || disallowed.contains(name)) {
                if (child.getToolMap() != null) {
                    child.getToolMap().remove(name);
                }
                continue;
            }
            if (child.getTool(name) != null) {
                continue;
            }
            BaseTool tool = parentTools.getTool(name);
            if (tool == null) {
                tool = switch (name) {
                    case McpToolNames.TOOL_SEARCH -> new ToolSearchTool();
                    case McpToolNames.TOOL_DESCRIBE -> new ToolDescribeTool();
                    case McpToolNames.TOOL_CALL -> new ToolCallTool();
                    default -> null;
                };
            }
            if (tool != null) {
                child.addTool(tool);
            }
        }
    }
}
