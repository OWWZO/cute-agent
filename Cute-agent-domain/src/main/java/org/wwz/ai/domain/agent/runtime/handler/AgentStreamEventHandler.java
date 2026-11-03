package org.wwz.ai.domain.agent.runtime.handler;


import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

/**
 * Agent 运行事件到增量响应的转换端口。
 * <p>
 * 实现类只负责协议适配，执行事实和历史回放数据仍由 Agent runtime 与 Execution Ledger 提供。
 * 投影状态（累积器）由调用方持有并单写；端口不额外传入整轮事件列表，避免实现方
 * 误以为需要重放/复制事件。
 */
public interface AgentStreamEventHandler {
    /**
     * 把单个运行事件投影为增量响应，并就地推进调用方持有的累积器。
     *
     * @param request     当前执行命令，提供 requestId / agentType 等归属信息
     * @param response    当前单个流事件
     * @param eventResult 本轮聚合状态；实现会就地推进 taskOrder / messageOrder 等字段，
     *                    所有权仍归调用方，调用方必须按事件顺序串行调用，不得并发共享同一累积器
     * @return 增量结果，永不返回 null；实现内部异常必须转换为失败终态结果
     *         （如 {@code status=failed}、{@code complete=true}），不得把异常抛给调用方
     */
    AgentStreamResult handle(AgentExecutionCommand request,
                             AgentStreamEvent response,
                             AgentStreamAccumulator eventResult);
}
