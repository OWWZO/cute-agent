package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class UserQuestionPO {
    private Long id;
    private String questionId;
    private String userId;
    private String sessionId;
    private Long sourceRunId;
    private String sourceRequestId;
    private Long toolInvocationId;
    private String toolCallId;
    @ToString.Exclude
    private String questionsJson;
    @ToString.Exclude
    private String answersJson;
    private String status;
    private LocalDateTime expiresAt;
    private String resumeRequestId;
    @ToString.Exclude
    private String resumeContextJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
