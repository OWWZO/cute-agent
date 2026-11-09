package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通用 Trace 中的单条会话消息。
 *
 * <p>不包含 base64 图片、模型推理内容和原始工具结果。</p>
 */
@Getter
@Builder
public class ConversationMessageView {

    private String messageId;
    private String conversationId;
    private String turnId;
    private Integer turnIndex;
    private String requestId;
    private Long runId;
    private Integer seqNo;
    private String role;
    private String actor;
    private String content;
    private String toolCallId;
    private String toolCallsJson;
    private String messageKind;
    private String visibility;
    private Integer tokenEstimate;
    private LocalDateTime timestamp;
}
