package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 停止后台运行任务。
 * 操作 RuntimeBackgroundTaskRegistry，不是 Todo 列表。
 * 仅主 Agent 应挂载；子 Agent 工具池会剔除本工具。
 */
@Slf4j
public class TaskStopTool implements BaseTool {

    @Override
    public String getName() {
        return TaskToolNames.TASK_STOP;
    }

    @Override
    public String getDescription() {
        return "按 agentId 停止正在运行的后台子 Agent。"
                + "仅在用户明确要求取消/停止，或子 Agent runaway/有害时使用。"
                + "不要仅因已读够输出就 stop agent。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> agentId = new LinkedHashMap<>();
        agentId.put("type", "string");
        agentId.put("description", "子 Agent 的 agentId");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent_id", agentId);

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
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(before.getStatus())) {
                return ToolResultPayload.failure(
                        "TaskStop 失败：该子 Agent 非 running 状态（" + before.getStatus() + "）",
                        "Task not running: agentId=" + agentId + " status=" + before.getStatus(),
                        null,
                        "not_running");
            }
            RuntimeBackgroundTask stopped = context.requireBackgroundTasks()
                    .stop(before.getId()).orElse(before);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("message", "已停止后台子 Agent " + agentId + "（已发送取消令牌）");
            fields.put("agent_id", agentId);
            fields.put("task_type", stopped.getType());
            fields.put("status", stopped.getStatus());
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
}
