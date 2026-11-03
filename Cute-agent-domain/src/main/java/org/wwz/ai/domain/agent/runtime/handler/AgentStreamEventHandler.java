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
    AgentStreamResult handle(AgentExecutionCommand request,
                             AgentStreamEvent response,
                             AgentStreamAccumulator eventResult);
}
