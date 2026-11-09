package org.wwz.ai.domain.agent.memory;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 工作记忆压缩事件（表 ai_agent_working_memory_compaction）。
 * <p>{@code beforeMessagesJson}/{@code afterMessagesJson} 是审计快照，可能包含整段上下文历史。
 * 构造时强制截断到 {@link #MAX_PAYLOAD_CHARS}，避免单行数百 KB 的 JSON 直接落库。</p>
 */
@Data
@Builder
@NoArgsConstructor
public class WorkingMemoryCompactionEvent {

    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    /** 审计快照单字段最大字符数；超限截断并追加标记。 */
    public static final int MAX_PAYLOAD_CHARS = 64_000;

    private Long id;
    private String sessionId;
    private String triggerRequestId;
    private String compactRequestId;
    private String strategy;
    private Integer status;
    private Integer beforeTokens;
    private Integer afterTokens;
    private Integer beforeMessageCount;
    private Integer afterMessageCount;
    private Integer thresholdTokens;
    private String summaryText;
    private String beforeMessagesJson;
    private String afterMessagesJson;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;

    /**
     * Lombok {@code @Builder} 复用本构造器，保证任何构造路径都经过截断。
     */
    public WorkingMemoryCompactionEvent(Long id,
                                        String sessionId,
                                        String triggerRequestId,
                                        String compactRequestId,
                                        String strategy,
                                        Integer status,
                                        Integer beforeTokens,
                                        Integer afterTokens,
                                        Integer beforeMessageCount,
                                        Integer afterMessageCount,
                                        Integer thresholdTokens,
                                        String summaryText,
                                        String beforeMessagesJson,
                                        String afterMessagesJson,
                                        String errorMessage,
                                        LocalDateTime createTime,
                                        LocalDateTime updateTime,
                                        Integer deleted) {
        this.id = id;
        this.sessionId = sessionId;
        this.triggerRequestId = triggerRequestId;
        this.compactRequestId = compactRequestId;
        this.strategy = strategy;
        this.status = status;
        this.beforeTokens = beforeTokens;
        this.afterTokens = afterTokens;
        this.beforeMessageCount = beforeMessageCount;
        this.afterMessageCount = afterMessageCount;
        this.thresholdTokens = thresholdTokens;
        this.summaryText = summaryText;
        this.beforeMessagesJson = truncatePayload(beforeMessagesJson);
        this.afterMessagesJson = truncatePayload(afterMessagesJson);
        this.errorMessage = errorMessage;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.deleted = deleted;
    }

    private static String truncatePayload(String value) {
        if (value == null || value.length() <= MAX_PAYLOAD_CHARS) {
            return value;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            return "{\"truncated\":true,\"reason\":\"audit_payload_limit\","
                    + "\"originalChars\":" + value.length()
                    + ",\"sha256\":\"sha256:" + hash + "\"}";
        } catch (Exception e) {
            return "{\"truncated\":true,\"reason\":\"audit_payload_limit\","
                    + "\"originalChars\":" + value.length() + "}";
        }
    }
}
