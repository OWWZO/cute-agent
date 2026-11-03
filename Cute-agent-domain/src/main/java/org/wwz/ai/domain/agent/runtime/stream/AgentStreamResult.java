package org.wwz.ai.domain.agent.runtime.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * runtime 投影结果。
 * <p>字段使用运行时语义；Case mapper 负责将其转换为对外增量 frame。</p>
 * <p>{@code status} 与 {@code complete} 是两套并行状态，必须由同一处写入保持一致：
 * {@code running} 对应 {@code complete=false}，{@code success}/{@code failed} 对应 {@code complete=true}。
 * {@code sequence}/{@code elapsedMillis}/{@code tokenCount} 的 0 是合法值，无法用于判断「未设置」；
 * 需要区分时请结合 {@code status}/{@code complete}，不要假定 0 表示缺省。</p>
 * <p>{@code eventData}/{@code messages} 保存的是生产者持有的可变集合（实时链路在构造后仍会填充），
 * 因此不做构造期拷贝；{@code equals}/{@code hashCode} 排除这两个字段，避免帧进入哈希集合后
 * 因内容变化而丢失。</p>
 */
@Data
@Builder
@AllArgsConstructor
public class AgentStreamResult {

    /**
     * 无参构造必须显式补默认值：{@code @Builder.Default} 只对 builder 生效，
     * 字段初始化器会被 Lombok 移到 {@code $default$xxx()}，直接 new 的实时帧会得到 null。
     */
    public AgentStreamResult() {
        this.status = "running";
        this.contentDelta = "";
        this.content = "";
        this.contentType = "markdown";
        this.frameType = "result";
    }

    @Builder.Default
    private String status = "running";
    @Builder.Default
    private String contentDelta = "";
    @Builder.Default
    private String content = "";
    private boolean complete;
    private long elapsedMillis;
    private long tokenCount;
    @EqualsAndHashCode.Exclude
    private Map<String, Object> eventData;
    @Builder.Default
    private String contentType = "markdown";
    private String traceId;
    private String requestId;
    private boolean encrypted;
    private String query;
    @EqualsAndHashCode.Exclude
    private List<String> messages;
    @Builder.Default
    private String frameType = "result";
    private String errorMessage;
    private long sequence;
    private Long retryAfterMs;
}
