package org.wwz.ai.application.agent.stream;

import java.util.function.Consumer;

/**
 * 会话级事件总线：投影只负责赋序和发布，观察连接由 trigger Hub 订阅。
 */
public interface AgentSessionEventBus {

    /**
     * 向指定会话的观察连接发布一帧。
     * <p>{@code sessionId} 为空白或 {@code frame} 为 null 时应直接忽略。实现必须允许并发调用；
     * 当前实现按每个连接分别串行发送，但不额外按 {@code eventSeq} 排序，因此发布方仍应按
     * 预期帧序调用。单条连接发送失败不会回传给发布方，通常会记录日志并关闭该连接。</p>
     *
     * @param sessionId 目标会话 ID；空白值不产生投递
     * @param frame     待发布帧；null 不产生投递
     */
    void publish(String sessionId, AgentSessionStreamFrame frame);

    /**
     * 订阅一个会话后续发布的帧。
     *
     * <p>返回句柄必须在观察流结束时关闭，否则进程内订阅者会持续泄漏。默认实现
     * 保持旧测试替身兼容；生产 Hub 必须提供真实订阅能力。</p>
     */
    default AutoCloseable subscribe(String sessionId,
                                    Consumer<AgentSessionStreamFrame> subscriber) {
        return subscribe(sessionId, subscriber, null);
    }

    /**
     * 订阅会话帧，并在当前会话的活投影全部结束时触发一次完成通知。
     */
    default AutoCloseable subscribe(String sessionId,
                                    Consumer<AgentSessionStreamFrame> subscriber,
                                    Runnable onComplete) {
        return () -> {
        };
    }

    /**
     * 请求结束指定会话的观察连接；空白 {@code sessionId} 不产生操作。
     */
    default void completeSession(String sessionId) {
    }
}
