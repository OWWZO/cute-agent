package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 时间线目录项对应的账本事实来源。
 */
public enum TimelineItemSourceType {
    LLM,
    TOOL,
    ARTIFACT,
    RUN,
    SYSTEM
}
