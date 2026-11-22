package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentOwnershipResolver;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 停止后台运行任务。
 * 操作 RuntimeBackgroundTaskRegistry，不是 Todo 列表。
 * 所有非 dataAgent 的 Agent 都可使用，但只能停止自己的直接子 Agent。
 */
@Slf4j
public class TaskStopTool implements BaseTool {

    @Override
    public String getName() {
        return TaskToolNames.TASK_STOP;
    }

    @Override
    public String getDescription() {
        return "按 agentId 停止正在运行的直接子 Agent；默认只停目标任务。"
                + "仅在用户明确要求递归停止整棵子树时传 recursive=true。"
                + "仅在用户明确要求取消/停止，或子 Agent runaway/有害时使用。"
                + "不要仅因已读够输出就 stop agent。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> agentId = new LinkedHashMap<>();
        agentId.put("type", "string");
        agentId.put("description", "子 Agent 的 agentId");

        Map<String, Object> recursive = new LinkedHashMap<>();
        recursive.put("type", "boolean");
        recursive.put("description", "是否递归停止目标及其运行中的后代，默认 false");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent_id", agentId);
        properties.put("recursive", recursive);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("agent_id"));
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = ToolArgs.map(input);
            String agentId = ToolArgs.str(params.get("agent_id"));
            boolean recursive = params.containsKey("recursive")
                    ? coerceBoolean(params.get("recursive"), false)
                    : false;
            if (StringUtils.isBlank(agentId)) {
                return ToolResultPayload.failure(
                        "TaskStop 失败：agent_id 必填",
                        "TaskStop 失败：agent_id 必填",
                        null,
                        "missing agent_id");
            }
            Optional<RuntimeBackgroundTask> existing =
                    context.requireBackgroundTasks().findLatestByAgentId(agentId);
            if (existing.isEmpty()) {
                return ToolResultPayload.failure(
                        "TaskStop 失败：没有 agentId=" + agentId + " 的后台子 Agent",
                        "Task not found: agentId=" + agentId,
                        null,
                        "not_found");
            }
            RuntimeBackgroundTask before = existing.get();
            if (!SubAgentOwnershipResolver.ownsDirectChild(context, before, agentId)
                    && !SubAgentOwnershipResolver.ownsLegacyChild(context, before, agentId)) {
                String owningParent = SubAgentOwnershipResolver.findOwningParentAgentId(
                        context, before, agentId);
                String hint = StringUtils.isBlank(owningParent)
                        ? "请先唤醒/联系派发该 Agent 的父 Agent，再由父 Agent 停止。"
                        : "请先唤醒/联系直接父 Agent " + owningParent + "，再由父 Agent 停止。";
                return ToolResultPayload.failure(
                        "TaskStop 失败：agentId=" + agentId + " 不是你的直接子 Agent。" + hint,
                        "not_owned: agentId=" + agentId,
                        null,
                        "not_owned");
            }
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(before.getStatus())) {
                return ToolResultPayload.failure(
                        "TaskStop 失败：该子 Agent 非 running 状态（" + before.getStatus() + "）",
                        "Task not running: agentId=" + agentId + " status=" + before.getStatus(),
                        null,
                        "not_running");
            }
            List<String> stoppedAgentIds = new ArrayList<>();
            RuntimeBackgroundTask stopped = null;
            RuntimeBackgroundTask stoppedTarget = null;
            List<RuntimeBackgroundTask> targets = recursive
                    ? context.requireBackgroundTasks().listRunningSubtree(agentId)
                    : List.of(before);
            for (RuntimeBackgroundTask task : targets) {
                Optional<RuntimeBackgroundTask> stopResult = context.requireBackgroundTasks()
                        .stop(task.getId());
                if (stopResult.isPresent()) {
                    stopped = stopResult.get();
                    if (before.getId().equals(task.getId())) {
                        stoppedTarget = stopped;
                    }
                    stoppedAgentIds.add(StringUtils.defaultIfBlank(
                            stopped.getAgentId(), stopped.getId()));
                }
            }
            if (stopped == null) {
                // 任务行已不在注册表：什么都没停，不能回退成停止前的快照谎报成功。
                return ToolResultPayload.failure(
                        "TaskStop 失败：agentId=" + agentId + " 的任务已不存在（可能已被并发清理）",
                        "Task not found: agentId=" + agentId,
                        null,
                        "not_found");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("message", recursive
                    ? "已递归停止 " + stoppedAgentIds.size()
                            + " 个后台子 Agent（目标 " + agentId + " 及后代）"
                    : "已停止后台子 Agent " + agentId);
            fields.put("agent_id", agentId);
            fields.put("recursive", recursive);
            RuntimeBackgroundTask resultTask = stoppedTarget == null ? before : stoppedTarget;
            fields.put("task_type", resultTask.getType());
            fields.put("status", resultTask.getStatus());
            fields.put("stoppedAgentIds", stoppedAgentIds);
            if (StringUtils.isNotBlank(stopped.getAgentType())) {
                fields.put("agentType", stopped.getAgentType());
            }
            if (StringUtils.isNotBlank(stopped.getCommand())) {
                fields.put("command", stopped.getCommand());
            }
            return ToolResultPayload.okData(TaskToolNames.TASK_STOP, fields);
        } catch (Exception e) {
            log.warn("TaskStop failed", e);
            String msg = "TaskStop 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    private static boolean coerceBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        return defaultValue;
    }
}
