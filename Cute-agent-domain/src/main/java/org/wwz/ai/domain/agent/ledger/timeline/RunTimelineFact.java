package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 唯一拥有最终答复身份的 run-final 事实。
 */
public record RunTimelineFact(
        TimelineItemKey.RunFinal key,
        String sessionId,
        String runId,
        String agentId,
        TimelineItemStatus status
) implements TimelineFact {

    public RunTimelineFact {
        if (key == null) {
            throw new IllegalArgumentException("run-final key must not be null");
        }
        sessionId = requireText(sessionId, "sessionId");
        runId = requireText(runId, "runId");
        agentId = requireText(agentId, "agentId");
        if (status == null
                || status == TimelineItemStatus.RUNNING
                || status == TimelineItemStatus.WAITING_INPUT) {
            throw new IllegalArgumentException("run-final status must be terminal");
        }
    }

    @Override
    public TimelineItemKind kind() {
        return TimelineItemKind.ASSISTANT_MESSAGE;
    }

    @Override
    public TimelineItemPhase phase() {
        return TimelineItemPhase.FINAL;
    }

    @Override
    public String parentItemId() {
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
