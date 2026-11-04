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
     * 返回生成 todo id 所用的单调高水位。
     * <p>实现必须包含软删除行：{@code replaceTodos} 会软删旧行后重新发放 id，
     * 若这里退回为当前可见行的最大值，后续生成的新 id 会与软删除行冲突。</p>
     */
    int loadTodoHighWaterMark(String sessionId);

    void upsertTodo(String sessionId, SessionTaskItem item);

    void deleteTodo(String sessionId, String taskId);

    /**
     * 整表替换：软删旧行后写入新列表（可为空列表表示清空）。
     */
    void replaceTodos(String sessionId, List<SessionTaskItem> items);

    /** 可能返回 {@code null}；调用方需做空值兜底。 */
    List<RuntimeBackgroundTask> loadBackgroundTasks(String sessionId);

    Optional<RuntimeBackgroundTask> findBackgroundTask(String sessionId, String taskId);

    /**
     * 后台任务只做 upsert，没有删除操作：完成/失败/停止的任务行会保留并随会话重新 hydrate。
     * 旧行清理由持久化实现或外部保留策略负责，不在本端口约定内。
     */
    void upsertBackgroundTask(String sessionId, RuntimeBackgroundTask task);
}
