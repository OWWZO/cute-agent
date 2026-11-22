package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight, keyset-paged session history.
 */
@Data
@EqualsAndHashCode
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryPage {

    private String sessionId;

    private String title;

    private Integer status;

    /** 会话绑定的主模式：normal / cluster。 */
    private String collaborationMode;

    /** 普通模式绑定的主 Agent key；集群模式为空。 */
    private String mainAgentKey;

    /** 会话级模型标识；{@code null} 表示用户从未显式选择过。 */
    private String model;

    /** 是否开启深度思考；{@code null} 表示未显式选择。 */
    private Boolean thinking;

    /** 思考强度（low/medium/high）；{@code null} 表示用默认。 */
    private String thinkingEffort;

    private String latestRequestId;

    private String latestQueryPreview;

    private String latestSummaryPreview;

    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    /** 本页 run 摘要；不参与相等性判断，默认与生产方一致使用不可变列表。 */
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<ConversationRunSummary> runs = List.of();

    private String nextCursor;

    @Builder.Default
    private boolean hasMore = false;
}
