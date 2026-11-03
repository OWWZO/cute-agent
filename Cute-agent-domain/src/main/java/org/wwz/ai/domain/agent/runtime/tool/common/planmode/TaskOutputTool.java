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

    /**
     * block=true 的最长阻塞时间。任务可能因进程重启/执行器丢任务而永远等不到终态，
     * 无界等待会挂死整个 Agent run；到点返回 not_ready，让模型决定继续等待还是放弃。
     */
    private static final long MAX_BLOCK_WAIT_MILLIS = 600_000L;

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
            String status = task.getStatus();
            if (shouldBlock && RuntimeBackgroundTask.STATUS_RUNNING.equals(status)) {
                Optional<RuntimeBackgroundTask> awaited = context.requireBackgroundTasks()
                        .awaitTerminal(taskId, MAX_BLOCK_WAIT_MILLIS);
                if (awaited.isEmpty()) {
                    // 等待期间任务行消失：不能再把等待前的快照当成真实状态回报。
                    retrieval = "not_found";
                    status = RuntimeBackgroundTask.STATUS_LOST;
                } else {
                    task = awaited.get();
                    status = task.getStatus();
                    if (RuntimeBackgroundTask.STATUS_RUNNING.equals(status)) {
                        retrieval = "not_ready";
                    }
                }
            } else if (RuntimeBackgroundTask.STATUS_RUNNING.equals(status)) {
                retrieval = "not_ready";
            }

            Map<String, Object> fields = new LinkedHashMap<>();
            // 键名当前是历史混合风格（retrieval_status/agent_id/task_type 为 snake_case，
            // agentType/totalToolUseCount/totalDurationMs/endedAtMs 为 camelCase）。
            // 这些键直接进模型上下文与回放，统一改名属于行为变更，见 W3 维护批次记录。
            fields.put("retrieval_status", retrieval);
            fields.put("agent_id", task.getAgentId());
            fields.put("task_type", task.getType());
            fields.put("status", status);
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
            fields.put("message", buildMessage(retrieval, status, task));
            return ToolResultPayload.okData(TaskToolNames.TASK_OUTPUT, fields);
        } catch (Exception e) {
            log.warn("TaskOutput failed", e);
            String msg = "TaskOutput 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    private static String buildMessage(String retrieval, String status, RuntimeBackgroundTask task) {
        if ("not_ready".equals(retrieval)) {
            return "任务仍在运行。block=false 可再次查看；block=true 将等到终态。";
        }
        if ("not_found".equals(retrieval)) {
            return "任务已丢失：等待期间无法再找到该任务。";
        }
        if (RuntimeBackgroundTask.STATUS_COMPLETED.equals(status)) {
            return "任务已完成。";
        }
        if (RuntimeBackgroundTask.STATUS_STOPPED.equals(status)) {
            return "任务已停止。";
        }
        if (RuntimeBackgroundTask.STATUS_FAILED.equals(status)) {
            return "任务失败：" + StringUtils.defaultString(task.getErrorMsg());
        }
        if (RuntimeBackgroundTask.STATUS_LOST.equals(status)) {
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
