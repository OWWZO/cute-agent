package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史详情聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryDetail {

    private String sessionId;

    private String title;

    private Integer status;

    private Boolean deepThink;

    /**
     * 会话累计 run 数。注意 {@link #runs} 可能因缺少 requestId 被过滤，
     * 因此 {@code runs.size()} 不保证等于本字段，计数与明细是两套口径。
     */
    private Integer runCount;

    /** 会话累计完成 run 数；同样可能大于 {@link #runs} 的条数。 */
    private Integer finishedRunCount;

    /** 会话累计失败 run 数；同样可能大于 {@link #runs} 的条数。 */
    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    @Builder.Default
    private List<ConversationRunDetail> runs = new ArrayList<>();

    /**
     * setter 路径（Jackson/反序列化）同样保证非 null。
     */
    public void setRuns(List<ConversationRunDetail> runs) {
        this.runs = runs == null ? new ArrayList<>() : runs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationRunDetail {

        private String requestId;

        private Integer status;

        private String queryText;

        private String finalSummaryText;

        private LocalDateTime startedAt;

        private LocalDateTime finishedAt;

        /** 本轮最后一次 LLM 调用的上下文占用快照。 */
        private ContextUsagePayload contextUsage;

        @Builder.Default
        private List<AgentStreamResult> replayFrames = new ArrayList<>();
    }
}
