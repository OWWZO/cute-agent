package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 时间线目录状态，与执行账本状态解耦。
 */
public enum TimelineItemStatus {
    RUNNING,
    WAITING_INPUT,
    COMPLETED,
    FAILED,
    CANCELLED
}
