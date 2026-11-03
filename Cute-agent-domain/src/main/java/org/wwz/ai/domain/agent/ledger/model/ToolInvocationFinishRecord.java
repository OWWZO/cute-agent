package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.time.LocalDateTime;

/**
 * 完成工具调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationFinishRecord {

    private Long toolInvocationId;

    private Long runId;

    private String requestId;

    private String sessionId;

    private String toolCallId;

    private String toolName;

    /**
     * 终态状态，取值必须是 {@code ExecutionLedgerConstants.STATUS_*} 中已登记的常量；
     * 该值会原样写入 ledger 行并参与终态守卫，null 或未登记值会破坏结算判断。
     */
    private Integer status;

    private String llmObservation;

    /** 工具完整结果 JSON（历史回放/前端用）；与 llmObservation 独立写入 */
    private String toolResult;

    private ToolStructuredOutput structuredOutput;

    private String errorMsg;

    private LocalDateTime finishedAt;
}
