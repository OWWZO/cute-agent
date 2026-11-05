package org.wwz.ai.domain.agent.adapter.port;

import org.wwz.ai.domain.agent.runtime.printer.Printer;

/**
 * 子会话独立流端口。
 * <p>
 * Phase 3 SSE 隔离用：子 Agent 的事件不再透传父流，而是写入子会话自己的投影流，
 * 由 {@code GET /api/agent/session/{childSessionId}/stream} 按需订阅。
 * <p>
 * 契约：
 * <ul>
 *   <li>{@link #openChildSessionStream} 必须在 child run 启动前调用；实现负责把投影注册进
 *       SessionProjectionRegistry，使 SSE 端点按 sessionId 自然可达；</li>
 *   <li>{@link #closeChildSessionStream} 在 run 终态/异常时调用，注销投影；不得关闭父流；</li>
 *   <li>返回 null 表示无法隔离（实现缺资源），调用方应回退到旧的透传父流行为。</li>
 *   <li>同一 {@code childSessionId} 允许重复 open（retry/并发子 run）：实现每次 open 都返回独立的
 *       Printer，注册表中同时存在多个投影；close 必须传入与本次 open 配对的 Printer，只关闭该投影。
 *       传入 null 或非本实现产生的 Printer 时按 sessionId 兜底清理该会话的全部活动投影，
 *       因此调用方不得用旧 Printer 去关闭新一次 open。</li>
 * </ul>
 * <p><b>隔离要求（安全契约）</b>：{@code childSessionId} 是确定性 ID（{@code sub_{agentId}}），
 * 可被猜测/观察，<b>不能单独作为订阅凭证</b>。实现必须保证：</p>
 * <ul>
 *   <li>投影注册信息带上 {@code userId}（注册键或投影字段均可），使订阅方能够校验归属；</li>
 *   <li>SSE 订阅路径（{@code GET /api/agent/session/{childSessionId}/stream}）必须先校验
 *       调用者身份与投影记录中的 {@code userId} 一致；当前由
 *       {@code AgentRunFollowApplicationService#authorizeAndAttach} 在 attach 前完成会话归属校验，
 *       任何新增订阅入口不得绕过该校验。</li>
 * </ul>
 */
public interface SubAgentSessionStreamPort {

    /**
     * 打开子会话独立投影流。
     *
     * @param childSessionId 子会话 ID（{@code sub_{agentId}}）
     * @param userId         会话归属用户
     * @param requestId      子 run 的 requestId（与 ledger run 同一）
     * @param queryText      子 Agent 任务 prompt，用于投影初始 query
     * @param agentType      主 Agent 类型；子会话投影复用主聊天链的 handler
     * @return 子会话领域 Printer；无法隔离时返回 null
     */
    Printer openChildSessionStream(String childSessionId,
                                   String userId,
                                   String requestId,
                                   String queryText,
                                   Integer agentType);

    /**
     * 关闭并注销子会话投影流。
     *
     * @param childSessionId 子会话 ID
     * @param printer        {@link #openChildSessionStream} 返回的实例；空时按 sessionId 兜底清理
     */
    void closeChildSessionStream(String childSessionId, Printer printer);
}
