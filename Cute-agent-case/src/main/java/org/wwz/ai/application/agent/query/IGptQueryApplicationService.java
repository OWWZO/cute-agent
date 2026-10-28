package org.wwz.ai.application.agent.query;

/**
 * GPT 查询应用服务接口。
 * trigger 进入主聊天链路的唯一应用层入口：协议翻译、会话守卫、进程内调度与事件投影。
 */
public interface IGptQueryApplicationService {

    /**
     * 提交一轮 Agent 执行。不占用 SSE；观察走 GET session stream。
     */
    AgentQuerySubmitResult submitAgentQuery(GptQueryCommand params);

    /**
     * 在已有会话（含子会话）上发起一轮新的 run。
     * <p>与 {@link #submitAgentQuery} 共用同一条 dispatch 路径（launch gate + 投影 + 账本），
     * 区别只在命令构建：本方法不读前端 payload，而是从会话元数据重建运行时选择。</p>
     *
     * @param preferences 本轮用户显式选择；{@code null} 等价于全部沿用库里已存值
     */
    AgentQuerySubmitResult submitSessionQuery(String sessionId, String query, SessionPreferences preferences);
}
