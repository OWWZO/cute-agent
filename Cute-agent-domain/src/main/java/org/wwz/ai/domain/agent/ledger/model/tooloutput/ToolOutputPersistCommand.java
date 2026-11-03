package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * rich tool 输出落表命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolOutputPersistCommand {

    private Long toolInvocationId;

    private Long runId;

    private String requestId;

    private String requestSource;

    private String sessionId;

    private String toolCallId;

    private String toolName;

    /** 工具调用终态 code，取值受执行账本状态映射约束，见 ExecutionLedgerConstants.STATUS_*。 */
    private Integer status;

    private String errorMsg;

    private ToolStructuredOutput structuredOutput;
}
