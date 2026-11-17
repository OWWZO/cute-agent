package org.wwz.ai.application.agent.query;

/**
 * GPT 查询应用服务接口。
 * trigger 进入主聊天链路的唯一应用层入口：协议翻译、会话守卫、进程内调度与事件投影。
 */
public interface IGptQueryApplicationService {

    /**
     * 提交一轮 Agent 执行。不占用 SSE；观察走 GET session stream。
     *
     * @param params 请求参数，必须非 null；实现会立即读取 query、sessionId、
     *               requestId、deepThink 等字段，缺少必需字段时按输入错误拒绝
     */
    AgentQuerySubmitResult submitAgentQuery(GptQueryCommand params);

    /**
     * 在已有会话（含子会话）上发起一轮新的 run。
     * <p>与 {@link #submitAgentQuery} 共用同一条 dispatch 路径（launch gate + 投影 + 账本），
     * 区别只在命令构建：本方法不读前端 payload，而是从会话元数据重建运行时选择。</p>
     *
     * <p>{@code sessionId} 与 {@code query} 必须非空白，否则抛
     * {@link IllegalArgumentException}；会话不存在或不属于当前用户时抛
     * {@link org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException}。</p>
     * <p>{@code preferences} 为 {@code null} 时全部沿用会话已存值；非 null 时，其中每个 null
     * 字段表示本轮不显式选择，取会话元数据，非 null 字段覆盖会话元数据。返回 {@code accepted=true}
     * 仅表示 run 已被接受，执行与流关联异步发生。</p>
     *
     * @param preferences 本轮用户显式选择；{@code null} 等价于全部沿用库里已存值
     */
    default AgentQuerySubmitResult submitSessionQuery(String sessionId,
                                                      String query,
                                                      SessionPreferences preferences) {
        return submitSessionQuery(sessionId, query, preferences, null);
    }

    /**
     * 带客户端 requestId 的子会话提交。{@code requestId} 为空时保持原生成逻辑，
     * 非空时用于同会话并发转换的幂等键。
     */
    AgentQuerySubmitResult submitSessionQuery(String sessionId,
                                              String query,
                                              SessionPreferences preferences,
                                              String requestId);
}
