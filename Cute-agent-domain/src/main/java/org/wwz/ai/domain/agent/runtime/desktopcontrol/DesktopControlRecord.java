package org.wwz.ai.domain.agent.runtime.desktopcontrol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesktopControlRecord {
    private Long id;
    private String controlId;
    private String userId;
    private String sessionId;
    private String ownerKey;
    private Long sourceRunId;
    private String sourceRequestId;
    private Long toolInvocationId;
    private String toolCallId;
    private String reason;
    private String streamUrl;
    private Double holdUntil;
    /** 生命周期状态，取值必须来自 {@link DesktopControlStatuses} 的常量。 */
    private String status;
    private String resumeRequestId;
    /**
     * 由 {@code UserQuestionResumeContext#toJson} 序列化的续跑上下文。
     * 读取方必须容忍解析失败与结构漂移。
     */
    private String resumeContextJson;
}
