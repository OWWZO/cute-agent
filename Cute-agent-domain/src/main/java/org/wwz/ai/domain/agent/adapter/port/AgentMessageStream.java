package org.wwz.ai.domain.agent.adapter.port;

import java.io.IOException;

/**
 * 协议无关的流式输出端口。
 * domain 只依赖该端口发送增量事件，具体采用 SSE、WebSocket 还是其他协议由 trigger/case 侧适配。
 *
 * <p>生命周期与并发契约（所有实现必须一致，当前以 SSE 实现为准）：</p>
 * <ul>
 *   <li>{@link #complete()} / {@link #completeWithError(Throwable)} 幂等且互斥：
 *       只有第一次调用生效，之后的调用（含两者交叉）静默忽略；</li>
 *   <li>进入终态后再调用 {@link #send(Object)} 静默丢弃，不再抛 {@link IOException}；</li>
 *   <li>{@link #onAbort(Runnable)} 可重复注册且回调累加，每个回调至多执行一次：
 *       注册时若链路已中断，立即在注册线程执行；否则在检测到中断的线程上执行；</li>
 *   <li>{@link #isAborted()} 一旦返回 {@code true} 不会回退。</li>
 * </ul>
 */
public interface AgentMessageStream {

    /**
     * 发送一帧增量事件。
     *
     * @throws IOException 下游链路故障（网络/序列化失败）。客户端主动断开必须归类为
     *                     abort（{@link #onAbort} + {@link #isAborted()}），不得作为 IOException 上抛。
     */
    void send(Object payload) throws IOException;

    void complete();

    void completeWithError(Throwable throwable);

    /**
     * 注册下游异常中断后的回调。
     * 典型场景是浏览器主动断开 SSE，此时调用方可以解绑观察流；是否取消后台 run
     * 由调用方根据业务语义决定，客户端断开本身不等于用户主动停止。
     * <p><b>实现必须覆盖本方法。</b>故意不提供空默认实现：漏实现会让断开的流永远挂在
     * {@code ActiveAgentRunRegistry} 上（见 {@code bindStream} 的 detach 回调），
     * 后续发送落到已关闭的传输层。确实没有中断语义的实现（如 IM 观察者）也必须显式
     * 声明为空操作，而不是依赖默认值。</p>
     */
    void onAbort(Runnable abortHandler);

    /**
     * 当前下游输出链路是否已经异常中断。
     */
    default boolean isAborted() {
        return false;
    }
}
