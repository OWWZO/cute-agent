package org.wwz.ai.domain.agent.runtime.tasklist;

import java.util.List;
import java.util.Optional;

/**
 * 会话 Todo + 后台任务持久化端口（非 Execution Ledger）。
 * 失败由实现方吞并日志；调用方以内存态为准继续服务。
 */
public interface TasklistPersistencePort {

    /** 可能返回 {@code null}；调用方需做空值兜底。 */
    List<SessionTaskItem> loadTodos(String sessionId);

    /**
     * 整表替换：软删旧行后写入新列表（可为空列表表示清空）。
     */
    void replaceTodos(String sessionId, List<SessionTaskItem> items);

    /** 可能返回 {@code null}；调用方需做空值兜底。 */
    List<RuntimeBackgroundTask> loadBackgroundTasks(String sessionId);

    Optional<RuntimeBackgroundTask> findBackgroundTask(String sessionId, String taskId);

    /** 直聊/唤醒按子会话反查仍在运行或等待的逻辑任务。 */
    default Optional<RuntimeBackgroundTask> findActiveBackgroundTaskByChildSessionId(String childSessionId) {
        return Optional.empty();
    }

    /** 单实例树控用的 owner 过滤；旧实现未支持时返回空列表。 */
    default List<RuntimeBackgroundTask> findRunningBackgroundTasksByOwner(String rootSessionId,
                                                                          String ownerAgentId) {
        return List.of();
    }

    /**
     * 后台任务只做 upsert，没有删除操作：完成/失败/停止的任务行会保留并随会话重新 hydrate。
     * 旧行清理由持久化实现或外部保留策略负责，不在本端口约定内。
     */
    void upsertBackgroundTask(String sessionId, RuntimeBackgroundTask task);
}
