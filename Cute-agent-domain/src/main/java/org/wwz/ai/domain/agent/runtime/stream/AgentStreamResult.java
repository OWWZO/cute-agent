package org.wwz.ai.domain.agent.runtime.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * runtime 投影结果。
 * <p>字段使用运行时语义；Case mapper 负责将其转换为对外增量 frame。</p>
 * <p>{@code status} 与 {@code complete} 是两套并行状态，必须由同一处写入保持一致：
 * {@code running} 对应 {@code complete=false}，{@code success}/{@code failed} 对应 {@code complete=true}。
 * {@code sequence}/{@code elapsedMillis}/{@code tokenCount} 的 0 是合法值，无法用于判断「未设置」；
 * 需要区分时请结合 {@code status}/{@code complete}，不要假定 0 表示缺省。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamResult {

    @Builder.Default
    private String status = "running";
    @Builder.Default
    private String contentDelta = "";
    @Builder.Default
    private String content = "";
    private boolean complete;
    private long elapsedMillis;
    private long tokenCount;
    private Map<String, Object> eventData;
    @Builder.Default
    private String contentType = "markdown";
    private String traceId;
    private String requestId;
    private boolean encrypted;
    private String query;
    private List<String> messages;
    @Builder.Default
    private String frameType = "result";
    private String errorMessage;
    private long sequence;
    private Long retryAfterMs;
}
