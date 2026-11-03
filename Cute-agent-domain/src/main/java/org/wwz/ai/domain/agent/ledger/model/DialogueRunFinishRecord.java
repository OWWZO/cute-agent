package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 结束 run 的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueRunFinishRecord {

    /** 仅用于空值校验与失败日志定位；实际写入以 {@link #requestId} 查询到的 run 为准。 */
    private Long runId;

    /** 结束 run 的权威关联键。 */
    private String requestId;

    private Integer status;

    private String finalSummaryText;

    private String errorCode;

    private String errorMsg;

    private LocalDateTime finishedAt;
}
