package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * LLM 正文或 reasoning 的时间线事实。
 */
public record LlmTimelineFact(
        TimelineItemKey key,
        String sessionId,
        String runId,
        String agentId,
        TimelineItemPhase phase,
        TimelineItemStatus status,
        String parentItemId
) implements TimelineFact {

    public LlmTimelineFact {
        if (!(key instanceof TimelineItemKey.Llm) && !(key instanceof TimelineItemKey.LlmReasoning)) {
            throw new IllegalArgumentException("LLM fact requires an LLM key");
        }
        sessionId = requireText(sessionId, "sessionId");
        runId = requireText(runId, "runId");
        agentId = requireText(agentId, "agentId");
        if (phase == null || phase == TimelineItemPhase.FINAL) {
            throw new IllegalArgumentException("LLM fact phase must be INTERMEDIATE or SUMMARY");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    @Override
    public TimelineItemKind kind() {
        return key instanceof TimelineItemKey.LlmReasoning
                ? TimelineItemKind.REASONING
                : TimelineItemKind.ASSISTANT_MESSAGE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
