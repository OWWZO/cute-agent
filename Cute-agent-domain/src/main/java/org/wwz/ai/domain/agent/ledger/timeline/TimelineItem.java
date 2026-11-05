package org.wwz.ai.domain.agent.ledger.timeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 时间线目录项。
 *
 * <p>该对象只保存稳定标识、顺序和状态；完整正文仍由 Execution Ledger 持有。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineItem {

    private Long id;
    private String sessionId;
    private String runId;
    private String itemId;
    private String parentItemId;
    private String agentId;
    private String kind;
    private String phase;
    private String status;
    private Long orderKey;
    private Integer subOrder;
    private Long updatedSeq;
    private String sourceType;
    private Long llmInvocationId;
    private Long toolInvocationId;
    private Long artifactId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
