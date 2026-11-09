package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作记忆会话消息的只读联表行。
 */
@Data
@Builder
public class WorkingMemoryConversationRow {

    private Long turnId;
    private Long messageId;
    private String sessionId;
    private String requestId;
    private Long runId;
    private Integer turnSeq;
    private Integer seqNo;
    private String role;
    private String content;
    private String toolCallId;
    private String toolCallsJson;
    private String messageKind;
    private String visibility;
    private Integer tokenEstimate;
    private LocalDateTime createTime;
}
