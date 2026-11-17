package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 可以在 Execution Ledger 事务内物化为时间线目录项的事实。
 */
public sealed interface TimelineFact permits
        LlmTimelineFact,
        ToolTimelineFact,
        ArtifactTimelineFact,
        RunTimelineFact {

    TimelineItemKey key();

    String sessionId();

    String runId();

    String agentId();

    TimelineItemKind kind();

    TimelineItemPhase phase();

    TimelineItemStatus status();

    String parentItemId();
}
