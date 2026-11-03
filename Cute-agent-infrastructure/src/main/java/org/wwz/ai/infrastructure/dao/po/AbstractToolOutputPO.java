package org.wwz.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 输出表公共字段。
 * 仅在基础设施层内部用于序列化和反序列化，不向领域层泄漏。
 */
@Data
public abstract class AbstractToolOutputPO {

    private Long id;

    private Long toolInvocationId;

    private Long runId;

    private String requestId;

    private String requestSource;

    private String sessionId;

    private String toolCallId;

    /**
     * 工具调用终态 code；有效写入命令要求非空，取值约束见 ExecutionLedgerConstants.STATUS_*。
     * 手工构造 PO 时仍可能为 null。
     */
    private Integer status;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
