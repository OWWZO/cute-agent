package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * rich tool 输出落表命令。
 * <p>字段可空性：{@code requestId}、{@code toolCallId}、{@code toolName}、{@code status}、
 * {@code structuredOutput} 必须提供，否则写入方会直接跳过（不抛异常）；
 * {@code toolInvocationId}/{@code runId}/{@code sessionId} 允许为空——工作台生图等非 run 场景
 * 只提供请求级关联字段。校验由调用方在构造命令前完成，此处不做统一非空约束。</p>
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
