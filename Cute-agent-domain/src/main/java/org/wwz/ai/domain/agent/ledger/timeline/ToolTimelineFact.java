package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * Tool invocation 的时间线事实。
 */
public record ToolTimelineFact(
        TimelineItemKey.Tool key,
        String sessionId,
        String runId,
        String agentId,
        TimelineItemStatus status,
        String parentItemId,
        Long toolInvocationId
) implements TimelineFact {

    public ToolTimelineFact {
        if (key == null) {
            throw new IllegalArgumentException("tool key must not be null");
        }
        sessionId = requireText(sessionId, "sessionId");
        runId = requireText(runId, "runId");
        agentId = requireText(agentId, "agentId");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (toolInvocationId == null || toolInvocationId <= 0) {
            throw new IllegalArgumentException("toolInvocationId must be positive");
        }
    }

    @Override
    public TimelineItemKind kind() {
        return TimelineItemKind.TOOL_CALL;
    }

    @Override
    public TimelineItemPhase phase() {
        return TimelineItemPhase.INTERMEDIATE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
