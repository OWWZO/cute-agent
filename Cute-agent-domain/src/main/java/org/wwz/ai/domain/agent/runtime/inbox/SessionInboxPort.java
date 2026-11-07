package org.wwz.ai.domain.agent.runtime.inbox;

import java.util.Collection;
import java.util.List;

/**
 * 会话 Inbox 持久化端口。数据库是唯一事实源；内存队列只允许作为唤醒提示。
 */
public interface SessionInboxPort {

    /**
     * 幂等入队；同一 session + idempotencyKey 只保留一条。
     * 非法枚举、空必填字段或超出列长度的消息返回 {@code null}。
     */
    SessionInboxItem offer(SessionInboxItem item);

    /**
     * 原子认领一批待投递消息。用户消息固定排在后台通知之前。
     */
    List<SessionInboxItem> claimPending(String sessionId, String runId, String kind, int limit);

    /**
     * 消息随工作记忆成功落库后 ACK。
     */
    int ackDelivered(String sessionId, Collection<Long> itemIds, String runId);

    /**
     * Run 未成功收口时释放本次认领，避免消息一直停在 CLAIMED。
     */
    int releaseClaims(String sessionId, String runId);

    /**
     * 用户 Stop 后冻结用户消息，不自动开新 Run。
     */
    int holdPendingUserMessages(String sessionId);

    /**
     * 用户显式确认后释放 HELD 消息；key 为空时释放该会话全部 HELD 用户消息。
     */
    int releaseHeldUserMessages(String sessionId, String idempotencyKey);

    boolean hasOutstanding(String sessionId);

    boolean hasDeliverableKind(String sessionId, String kind);

    boolean hasHeldUserMessage(String sessionId);

    /**
     * 唤醒轮控制帧用于展示首条待投递用户输入。
     */
    String peekFirstDeliverableUserText(String sessionId);
}
