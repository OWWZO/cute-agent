package org.wwz.ai.domain.agent.runtime.inbox;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

/**
 * 会话级待投递消息。用户输入与后台通知共用同一套投递状态机。
 */
@Value
@Builder
public class SessionInboxItem {

    public static final String KIND_USER_MESSAGE = "USER_MESSAGE";
    public static final String KIND_BACKGROUND_NOTIFICATION = "BACKGROUND_NOTIFICATION";

    public static final String VISIBILITY_ALL = "ALL";
    public static final String VISIBILITY_MODEL_ONLY = "MODEL_ONLY";

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_CLAIMED = "CLAIMED";
    public static final String STATE_DELIVERED = "DELIVERED";
    public static final String STATE_HELD = "HELD";

    private Long id;
    private String sessionId;
    private String kind;
    private String visibility;
    private String state;
    private String idempotencyKey;
    @ToString.Exclude
    private String payloadJson;
    private String sourceRunId;
    private String claimedByRunId;
    private Long leaseUntilMs;
    @Builder.Default
    private Long createdAtMs = System.currentTimeMillis();
    private Long deliveredAtMs;

    public boolean isHeld() {
        return STATE_HELD.equals(state);
    }
}
