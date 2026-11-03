package org.wwz.ai.domain.agent.runtime.tasklist;

/**
 * 后台任务完成通知（仅模型可见）。
 * <p>由完成路径或崩溃补投从 {@link RuntimeBackgroundTask} 快照生成，生命周期不超过一次注入。</p>
 */
public record TaskNotification(
        String taskId,
        String agentId,
        String childSessionId,
        String agentType,
        String description,
        String status,
        String result,
        String errorMsg,
        Integer totalToolUseCount,
        Long totalDurationMs,
        String parentToolUseId,
        long createdAt) {

    public static TaskNotification from(RuntimeBackgroundTask task, String parentToolUseId) {
        if (task == null) {
            return null;
        }
        return new TaskNotification(
                task.getId(),
                task.getAgentId(),
                task.getChildSessionId(),
                task.getAgentType(),
                task.getDescription(),
                task.getStatus(),
                task.getOutput(),
                task.getErrorMsg(),
                task.getTotalToolUseCount(),
                task.getTotalDurationMs(),
                parentToolUseId,
                task.getEndedAtMs() == null ? System.currentTimeMillis() : task.getEndedAtMs());
    }

    public boolean completed() {
        return RuntimeBackgroundTask.STATUS_COMPLETED.equals(status);
    }
}
