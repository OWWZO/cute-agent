package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
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
    @ToString.Exclude
    private String toolResult;

    private ToolStructuredOutput structuredOutput;

    /** 错误信息；写入账本时截断到 2000 字符，调用方不能假定完整文本被保存。 */
    private String errorMsg;

    /**
     * 完成时间；{@code null} 表示尚未完成。
     * <p>后台派发 ACK 是 provisional 写（子 Agent 仍在跑），必须保持为 {@code null}，
     * 让账本行继续处于 RUNNING；真正结算时才能通过终态守卫。调用方不得用
     * {@code LocalDateTime.now()} 兜底该字段，否则会静默破坏后台结算。</p>
     */
    private LocalDateTime finishedAt;
}
