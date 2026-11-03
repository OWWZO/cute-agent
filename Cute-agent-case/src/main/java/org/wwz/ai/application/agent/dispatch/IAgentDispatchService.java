package org.wwz.ai.application.agent.dispatch;

import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

/**
 * Agent 应用层调度接口。
 * <p>实现负责选择执行策略并启动本轮执行；策略本身由
 * {@code AgentDispatchService} 按 bean 名称解析，新增策略不应改变该入口契约。</p>
 */
public interface IAgentDispatchService {

    /**
     * 同步调度一轮 Agent 执行。
     *
     * <p><b>执行语义：</b>方法会进入所选策略并等待本轮执行结束或抛错，不是仅投递后台任务。
     * 正常调用路径要求 {@code request} 与 {@code stream} 均非 null；request 中的
     * requestId、sessionId 和 agentType 是活动 run 注册与策略选择的关键字段。</p>
     *
     * <p><b>并发语义：</b>同一 session 的并发执行由运行时活动 run 注册表仲裁。若被拒绝，
     * 当前实现会把 {@code AgentConcurrentRunException} 转换为 failed 终态帧、关闭传入的流，
     * 然后正常返回；调用方不应再依赖捕获该异常判断并发冲突。</p>
     *
     * <p><b>异常契约：</b>未注册策略时抛业务异常；其他策略执行异常原样向上抛出。
     * 接口保留 {@code throws Exception} 以兼容现有执行树，调用方仍应按统一异常边界处理。</p>
     *
     * <p><b>生命周期：</b>正常执行路径的流终结由上游投影或调用方负责；调度器只在上述
     * 并发拒绝分支主动发送终态并 {@code complete()}。调用方必须保证流最终被关闭。</p>
     */
    void dispatch(AgentExecutionCommand request, AgentSessionStream stream) throws Exception;
}
