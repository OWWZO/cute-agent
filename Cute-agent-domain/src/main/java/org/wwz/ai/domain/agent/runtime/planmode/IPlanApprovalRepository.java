package org.wwz.ai.domain.agent.runtime.planmode;

import java.util.List;
import java.util.Optional;

/**
 * ExitPlanMode 计划审批交互附属状态仓储。
 */
public interface IPlanApprovalRepository {

    void insert(PlanApprovalRecord record);

    Optional<PlanApprovalRecord> findByApprovalId(String approvalId);

    Optional<PlanApprovalRecord> findByResumeRequestId(String resumeRequestId);

    /** 「开放」定义必须与 {@link PlanApprovalStatuses#OPEN} 一致（PENDING/RESUME_PENDING/RESUMING）。 */
    List<PlanApprovalRecord> listOpenBySessionId(String sessionId);

    /** 必须与 {@link #listOpenBySessionId} 使用同一组开放状态。 */
    boolean hasOpenBySessionId(String sessionId);

    /**
     * CAS PENDING → RESUME_PENDING；成功返回 true。
     * <p>返回 false 表示记录不存在、userId 不匹配或状态已冲突，三者不做区分。</p>
     */
    boolean casDecidePending(String approvalId, String userId, PlanApprovalDecision decision, String resumeRequestId);

    /**
     * CAS RESUME_PENDING → RESUMING；成功返回 true。
     * <p>返回 false 表示记录不存在、userId 不匹配或状态已冲突，三者不做区分。</p>
     */
    boolean casClaimResume(String resumeRequestId, String userId);

    boolean markAnswered(String approvalId);

    boolean markStatus(String approvalId, String status);

    boolean casCancel(String approvalId, String userId);

    int cancelBySourceRequestId(String sourceRequestId, String reason);
}
