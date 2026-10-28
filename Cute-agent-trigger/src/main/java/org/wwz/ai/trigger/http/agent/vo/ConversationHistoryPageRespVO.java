package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Session history summary page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryPageRespVO {

    private String sessionId;

    private String title;

    private String status;

    private Boolean deepThink;

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

    @Builder.Default
    private List<ConversationRunSummaryRespVO> runs = new ArrayList<>();

    private String nextCursor;

    @Builder.Default
    private boolean hasMore = false;
}
