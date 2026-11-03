package org.wwz.ai.application.agent.stream;

/**
 * 回放时把投影缓冲帧交给单条观察连接。
 * <p>传入的 frame 正常应非 null，且可能同时存在于回放缓冲和实时广播中。回调实现必须把
 * frame 视为只读，不得修改 {@code resultMap}、{@code messages} 等共享集合。</p>
 * <p>回调可能由不同调度线程触发，实现方不得假定只在单线程调用；如需维护内部状态，
 * 应自行保证线程安全。当前 Hub 在单条连接内部串行发送，但这不是接口的全局线程约束。</p>
 */
@FunctionalInterface
public interface StreamFrameConsumer {

    /**
     * 消费一帧回放数据。
     * <p>frame 为 null 表示调用方违反正常契约，实现可按编程错误处理。</p>
     * <p>抛出异常表示该帧未成功交付；当前续流路径会把异常视为暂时不可挂接并退避重试，
     * 因此实现只应把真实 I/O 或连接不可用错误作为可重试失败，不应吞掉编程错误。</p>
     */
    void accept(AgentSessionStreamFrame frame) throws Exception;
}
