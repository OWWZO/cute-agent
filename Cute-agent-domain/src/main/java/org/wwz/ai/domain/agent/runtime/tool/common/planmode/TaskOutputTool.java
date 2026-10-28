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
 * 读取后台任务输出。
 * 可阻塞等待终态；与 Todo 列表无关。
 */
@Slf4j
public class TaskOutputTool implements BaseTool {

    @Override
    public String getName() {
        return TaskToolNames.TASK_OUTPUT;
    }

    @Override
    public String getDescription() {
        return                 "获取后台子 Agent 的输出。"
                + "block=true（默认）时等到 completed/failed/stopped/lost；block=false 立即返回当前状态（可反复调用）。"
                + "agent_id 来自 Agent(run_in_background=true) 的回执。"
                + "等待或查看子 Agent 结果必须用本工具，不要用 workspace_list 轮询文件。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> agentId = new LinkedHashMap<>();
        agentId.put("type", "string");
        agentId.put("description", "子 Agent 的 agentId（Agent 后台派发回执里给出的那个）");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "boolean");
        block.put("description", "是否等待任务结束，默认 true");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent_id", agentId);
        properties.put("block", block);

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
                        "TaskOutput 失败：agent_id 必填",
                        "TaskOutput 失败：agent_id 必填",
                        null,
                        "missing agent_id");
            }

            boolean shouldBlock = params.containsKey("block")
                    ? coerceBoolean(params.get("block"), true)
                    : true;

            Optional<RuntimeBackgroundTask> found = context.requireBackgroundTasks().findLatestByAgentId(agentId);
            if (found.isEmpty()) {
                String sid = context.getSessionId() != null
                        ? context.getSessionId()
                        : context.getRequestId();
                return ToolResultPayload.failure(
                        "TaskOutput 失败：该会话下没有 agentId=" + agentId + " 的子 Agent"
                                + "（session=" + sid + "）",
                        "Task not found: agentId=" + agentId + " session=" + sid,
                        null,
                        "not_found");
            }

            RuntimeBackgroundTask task = found.get();
            String taskId = task.getId();
            String retrieval = "success";
            if (shouldBlock && RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                task = context.requireBackgroundTasks()
                        .awaitUntilTerminal(taskId, context::isRunCancelled)
                        .orElse(task);
                if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                    retrieval = "not_ready";
                }
            } else if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                retrieval = "not_ready";
            }

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("retrieval_status", retrieval);
            fields.put("agent_id", task.getAgentId());
            fields.put("task_type", task.getType());
            fields.put("status", task.getStatus());
            fields.put("description", task.getDescription());
            if (StringUtils.isNotBlank(task.getAgentType())) {
                fields.put("agentType", task.getAgentType());
            }
            if (StringUtils.isNotBlank(task.getOutput())) {
                fields.put("output", task.getOutput());
            }
            if (StringUtils.isNotBlank(task.getErrorMsg())) {
                fields.put("error", task.getErrorMsg());
            }
            if (task.getTotalToolUseCount() != null) {
                fields.put("totalToolUseCount", task.getTotalToolUseCount());
            }
            if (task.getTotalDurationMs() != null) {
                fields.put("totalDurationMs", task.getTotalDurationMs());
            }
            if (task.getEndedAtMs() != null) {
                fields.put("endedAtMs", task.getEndedAtMs());
            }
            fields.put("message", buildMessage(retrieval, task));
            return ToolResultPayload.okData(TaskToolNames.TASK_OUTPUT, fields);
        } catch (Exception e) {
            log.warn("TaskOutput failed", e);
            String msg = "TaskOutput 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    private static String buildMessage(String retrieval, RuntimeBackgroundTask task) {
        if ("not_ready".equals(retrieval)) {
            return "任务仍在运行。block=false 可再次查看；block=true 将等到终态。";
        }
        if (RuntimeBackgroundTask.STATUS_COMPLETED.equals(task.getStatus())) {
            return "任务已完成。";
        }
        if (RuntimeBackgroundTask.STATUS_STOPPED.equals(task.getStatus())) {
            return "任务已停止。";
        }
        if (RuntimeBackgroundTask.STATUS_FAILED.equals(task.getStatus())) {
            return "任务失败：" + StringUtils.defaultString(task.getErrorMsg());
        }
        if (RuntimeBackgroundTask.STATUS_LOST.equals(task.getStatus())) {
            return "任务已丢失：" + StringUtils.defaultString(task.getErrorMsg(), "服务端重启");
        }
        return "ok";
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
