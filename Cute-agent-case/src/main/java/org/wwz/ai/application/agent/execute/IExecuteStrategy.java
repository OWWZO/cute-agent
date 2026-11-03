package org.wwz.ai.application.agent.execute;

import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

/**
 * 应用层执行策略接口。
 * <p>策略 bean 的选择由 {@code AgentDispatchService} 负责，实现类只执行传入的请求并把
 * 流式输出适配到 {@link AgentSessionStream}；domain 只关注运行时内核。</p>
 * <p>实现通常由 Spring 以单例方式管理，必须保持无请求级可变状态，或自行保证线程安全。
 * 每一轮执行的上下文应只保存在方法参数、局部变量或线程安全的运行时对象中。</p>
 */
public interface IExecuteStrategy {

    /**
     * 同步执行一轮 Agent 请求，并把运行过程中的帧写入调用方提供的流。
     *
     * <p><b>参数契约：</b>正常调用路径要求 {@code request} 与 {@code stream} 均非 null；
     * 当前实现会读取请求中的 requestId、sessionId、userId 和 agentType，并立即绑定流。</p>
     *
     * <p><b>异常契约：</b>本方法保留宽泛的 {@code throws Exception}，调用方应把并发拒绝
     * （{@code AgentConcurrentRunException}）、用户取消和执行失败分别处理。实现会在异常路径
     * 尽量写入 ledger 的 FAILED/STOPPED 终态后原样抛出，但不会替调用方统一关闭观察流。</p>
     *
     * <p><b>生命周期：</b>实现负责注册活动 run 并绑定流；正常终态的传输层收口由上游投影或
     * 调度方完成。当前 {@code AgentDispatchService} 只在并发拒绝分支发送终态帧并
     * {@code complete()}，其他异常由调用方负责收尾。</p>
     */
    void execute(AgentExecutionCommand request, AgentSessionStream stream) throws Exception;
}
