package org.wwz.ai.domain.agent.runtime.planmode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanApprovalRecord {
    private Long id;
    private String approvalId;
    private String userId;
    private String sessionId;
    private Long sourceRunId;
    private String sourceRequestId;
    private Long toolInvocationId;
    private String toolCallId;
    /** 完整用户计划正文，禁止进入 toString / equals / hashCode。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String planContent;
    private String planFilePath;
    private PlanApprovalDecision decision;
    /** 生命周期状态，取值必须来自 {@link PlanApprovalStatuses} 的常量。 */
    private String status;
    private LocalDateTime expiresAt;
    private String resumeRequestId;
    /** 续跑上下文快照，禁止进入 toString / equals / hashCode。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String resumeContextJson;
}
