package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * Artifact 的时间线事实。每个 Artifact 必须挂在产生它的 Tool 下。
 */
public record ArtifactTimelineFact(
        TimelineItemKey.Artifact key,
        String sessionId,
        String runId,
        String agentId,
        String parentItemId,
        Long toolInvocationId
) implements TimelineFact {

    public ArtifactTimelineFact {
        if (key == null) {
            throw new IllegalArgumentException("artifact key must not be null");
        }
        sessionId = requireText(sessionId, "sessionId");
        runId = requireText(runId, "runId");
        agentId = requireText(agentId, "agentId");
        parentItemId = requireText(parentItemId, "parentItemId");
        if (!parentItemId.startsWith("tool:")) {
            throw new IllegalArgumentException("artifact parent must be a tool item");
        }
        if (toolInvocationId == null || toolInvocationId <= 0) {
            throw new IllegalArgumentException("toolInvocationId must be positive");
        }
    }

    @Override
    public TimelineItemKind kind() {
        return TimelineItemKind.ARTIFACT;
    }

    @Override
    public TimelineItemPhase phase() {
        return TimelineItemPhase.INTERMEDIATE;
    }

    @Override
    public TimelineItemStatus status() {
        return TimelineItemStatus.COMPLETED;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
