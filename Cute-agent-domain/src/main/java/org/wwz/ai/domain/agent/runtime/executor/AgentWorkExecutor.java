package org.wwz.ai.domain.agent.runtime.executor;

import java.util.concurrent.Executor;

/**
 * Agent 工作执行器契约。
 *
 * <p>业务层只依赖 Executor；额外的场景和请求标识仅用于装配层观测。</p>
 */
public interface AgentWorkExecutor extends Executor {

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
