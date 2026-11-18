package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;

/**
 * Run summary used by history pages.
 *
 * <p>The query is a preview; the final answer is complete. Rich tool output,
 * artifacts and replay frames are loaded by the single-run replay endpoint.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRunSummary {

    private String requestId;

    private String entryAgent;

    private Integer status;

    private String queryPreview;

    private String finalSummaryPreview;

    private String finalSummaryText;

    /** 本轮最后一次 LLM 调用的上下文占用快照，轻量历史页也会携带。 */
    private ContextUsagePayload contextUsage;

    private Integer llmCallCount;

    private Integer toolCallCount;

    private Integer artifactCount;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 由 run 表 {@code duration_ms} 列读取；与 startedAt/finishedAt 同源，不另行推导。 */
    private Long durationMs;

    /** 是否具备回放标识（当前按 requestId 非空派生），不代表回放数据一定完整。 */
    private Boolean hasReplay;
}
