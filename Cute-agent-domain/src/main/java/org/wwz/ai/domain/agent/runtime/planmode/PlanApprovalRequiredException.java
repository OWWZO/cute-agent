package org.wwz.ai.domain.agent.runtime.planmode;

/**
 * ExitPlanMode 让步信号：不阻塞线程，由上层持久化断点并结束当前 run。
 */
public class PlanApprovalRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String planContent;
    private final String planFilePath;
    private final String toolCallId;

    public PlanApprovalRequiredException(String planContent, String planFilePath, String toolCallId) {
        // 控制流信号不需要栈；带上定位信息，避免落到未特判的处理器时无法关联到具体工具调用。
        super("PLAN_APPROVAL_REQUIRED toolCallId=" + toolCallId + " planFile=" + planFilePath,
                null, false, false);
        this.planContent = planContent;
        this.planFilePath = planFilePath;
        this.toolCallId = toolCallId;
    }

    public String getPlanContent() {
        return planContent;
    }

    public String getPlanFilePath() {
        return planFilePath;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}
