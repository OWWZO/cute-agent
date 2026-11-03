package org.wwz.ai.domain.agent.runtime.executor;

import java.util.concurrent.Executor;

/**
 * Agent 工作执行器契约。
 *
 * <p>业务层只依赖 Executor；额外的场景和请求标识仅用于装配层观测。</p>
 */
public interface AgentWorkExecutor extends Executor {

    /**
     * 异步提交任务，并携带仅用于观测的场景与请求标识。
     * <p>{@code command} 必须非 null（实现会做空值校验）；{@code scene} / {@code requestId} 可为 null
     * （默认重载即传 {@code "unknown"} / {@code null}），实现不得假设 requestId 非空。方法立即返回，
     * 不等待任务执行；执行器满载或已关闭时抛出 {@link java.util.concurrent.RejectedExecutionException}，
     * 由调用方决定降级或传播。</p>
     */
    void execute(Runnable command, String scene, String requestId);

    /**
     * 通用 {@link Executor} 入口的降级路径：场景记为 {@code "unknown"}、请求标识为 {@code null}。
     * <p>需要事件归属/链路关联的调用方应使用三参重载。</p>
     */
    @Override
    default void execute(Runnable command) {
        execute(command, "unknown", null);
    }
}
