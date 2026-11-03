package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 结束 run 的命令对象。
 * <p>构造后即不可变，避免命令交给 {@code finishRun} 后被其他层改动。</p>
 * <p>status 必须是可写入 run 的状态：RUNNING 或未知值会把「结束」写成一条仍在运行的账本，
 * 因此在构造期直接拒绝；WAITING_INPUT 是等待外部输入的合法中间态，允许写入。</p>
 */
@Getter
@EqualsAndHashCode
@ToString
public class DialogueRunFinishRecord {

    /** 仅用于空值校验与失败日志定位；实际写入以 {@link #requestId} 查询到的 run 为准。 */
    private final Long runId;

    /** 结束 run 的权威关联键。 */
    private final String requestId;

    private final Integer status;

    private final String finalSummaryText;

    private final String errorCode;

    private final String errorMsg;

    private final LocalDateTime finishedAt;

    @Builder
    public DialogueRunFinishRecord(Long runId,
                                   String requestId,
                                   Integer status,
                                   String finalSummaryText,
                                   String errorCode,
                                   String errorMsg,
                                   LocalDateTime finishedAt) {
        if (!isWritableStatus(status)) {
            throw new IllegalArgumentException("DialogueRunFinishRecord.status 非法: " + status);
        }
        this.runId = runId;
        this.requestId = requestId;
        this.status = status;
        this.finalSummaryText = finalSummaryText;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.finishedAt = finishedAt;
    }

    private static boolean isWritableStatus(Integer status) {
        if (status == null) {
            return false;
        }
        return status == ExecutionLedgerConstants.STATUS_SUCCESS
                || status == ExecutionLedgerConstants.STATUS_FAILED
                || status == ExecutionLedgerConstants.STATUS_TIMEOUT
                || status == ExecutionLedgerConstants.STATUS_STOPPED
                || status == ExecutionLedgerConstants.STATUS_WAITING_INPUT;
    }
}
