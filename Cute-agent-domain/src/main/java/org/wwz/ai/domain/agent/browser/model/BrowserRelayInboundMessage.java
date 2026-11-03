package org.wwz.ai.domain.agent.browser.model;

import java.util.Objects;

/**
 * A decoded relay message. JSON framing is handled by the Trigger mapper
 * before this object reaches the application or domain layers.
 */
public record BrowserRelayInboundMessage(String userId,
                                         String connectionId,
                                         Kind kind,
                                         BrowserCommandResult commandResult,
                                         BrowserTabMetadata tabMetadata) {

    public enum Kind {
        HEARTBEAT,
        TAB_CHANGED,
        COMMAND_RESULT
    }

    public BrowserRelayInboundMessage {
        userId = requireNonBlank(userId, "userId");
        connectionId = requireNonBlank(connectionId, "connectionId");
        kind = Objects.requireNonNull(kind, "kind");
        // kind 与 payload 必须自洽：消费者按 kind 分派后直接解引用对应 payload。
        switch (kind) {
            case HEARTBEAT -> {
                if (commandResult != null || tabMetadata != null) {
                    throw new IllegalArgumentException("heartbeat must not carry payloads");
                }
            }
            case TAB_CHANGED -> {
                if (tabMetadata == null || commandResult != null) {
                    throw new IllegalArgumentException("tab-changed requires tabMetadata and no commandResult");
                }
            }
            case COMMAND_RESULT -> {
                if (commandResult == null) {
                    throw new IllegalArgumentException("command-result requires commandResult");
                }
            }
        }
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    public static BrowserRelayInboundMessage heartbeat(String userId, String connectionId) {
        return new BrowserRelayInboundMessage(userId, connectionId, Kind.HEARTBEAT, null, null);
    }

    public static BrowserRelayInboundMessage tabChanged(String userId,
                                                         String connectionId,
                                                         BrowserTabMetadata metadata) {
        return new BrowserRelayInboundMessage(userId, connectionId, Kind.TAB_CHANGED, null, metadata);
    }

    public static BrowserRelayInboundMessage commandResult(String userId,
                                                            String connectionId,
                                                            BrowserCommandResult result) {
        return new BrowserRelayInboundMessage(userId, connectionId, Kind.COMMAND_RESULT, result, null);
    }
}
