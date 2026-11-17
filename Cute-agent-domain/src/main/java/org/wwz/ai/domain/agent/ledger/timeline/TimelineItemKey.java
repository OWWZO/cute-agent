package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 持久化时间线项的稳定身份。
 *
 * <p>身份只能由 Execution Ledger 的真实主键派生，禁止使用 messageId、临时 UUID
 * 或 SSE 序号兜底。</p>
 */
public sealed interface TimelineItemKey permits
        TimelineItemKey.Llm,
        TimelineItemKey.LlmReasoning,
        TimelineItemKey.Tool,
        TimelineItemKey.Artifact,
        TimelineItemKey.RunFinal {

    String encode();

    record Llm(long invocationId) implements TimelineItemKey {

        public Llm {
            requirePositive(invocationId, "invocationId");
        }

        @Override
        public String encode() {
            return "llm:" + invocationId;
        }
    }

    record LlmReasoning(long invocationId) implements TimelineItemKey {

        public LlmReasoning {
            requirePositive(invocationId, "invocationId");
        }

        @Override
        public String encode() {
            return "llm:" + invocationId + ":reasoning";
        }
    }

    record Tool(String toolCallId) implements TimelineItemKey {

        public Tool {
            toolCallId = requireText(toolCallId, "toolCallId");
        }

        @Override
        public String encode() {
            return "tool:" + toolCallId;
        }
    }

    record Artifact(long artifactId) implements TimelineItemKey {

        public Artifact {
            requirePositive(artifactId, "artifactId");
        }

        @Override
        public String encode() {
            return "artifact:" + artifactId;
        }
    }

    record RunFinal(String requestId) implements TimelineItemKey {

        public RunFinal {
            requestId = requireText(requestId, "requestId");
        }

        @Override
        public String encode() {
            return "run:" + requestId + ":final";
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
