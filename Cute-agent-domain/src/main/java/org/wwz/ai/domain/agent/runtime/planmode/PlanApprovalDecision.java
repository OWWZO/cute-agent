package org.wwz.ai.domain.agent.runtime.planmode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户对 ExitPlanMode 的决定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanApprovalDecision {

    /**
     * 是否批准。缺失/反序列化未设置时默认 false，即按「拒绝」处理；
     * 调用方不能把 false 当作「用户明确拒绝」的唯一证据。
     */
    private boolean approved;
    /** 拒绝时的反馈；批准时可空 */
    private String feedback;
    /** 用户编辑后的计划正文（可选，覆盖 pending 中的 plan） */
    private String editedPlanContent;
}
