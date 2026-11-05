package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 时间线项的业务类型。该类型只描述目录语义，不承载正文。
 */
public enum TimelineItemKind {
    ASSISTANT_MESSAGE,
    REASONING,
    TOOL_CALL,
    ARTIFACT,
    PLAN,
    HITL,
    SUBAGENT,
    SYSTEM_NOTICE
}
