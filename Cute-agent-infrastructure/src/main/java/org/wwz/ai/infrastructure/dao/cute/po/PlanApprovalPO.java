package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class PlanApprovalPO {
    private Long id;
    private String approvalId;
    private String userId;
    private String sessionId;
    private Long sourceRunId;
    private String sourceRequestId;
    private Long toolInvocationId;
    private String toolCallId;
    @ToString.Exclude
    private String planContent;
    private String planFilePath;
    @ToString.Exclude
    private String decisionJson;
    /** 审批状态，取值来自 PlanApprovalStatuses。 */
    private String status;
    private LocalDateTime expiresAt;
    private String resumeRequestId;
    @ToString.Exclude
    private String resumeContextJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 软删标记：0=有效，1=已删除。 */
    private Integer deleted;
}
