package org.wwz.ai.domain.agent.ledger.timeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 对外时间线项。目录字段来自窄表，payload 由 Ledger 事实按请求即时组装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineItemView {

    private String protocolVersion;
    private String eventType;
    private String eventId;
    private String runId;
    private String sessionId;
    private long seq;
    /** 仅用于 live SSE 续流；与 committed orderKey 无关系。 */
    private long eventSeq;
    private String itemId;
    private String parentItemId;
    private String agentId;
    private String kind;
    private String phase;
    private String status;
    private long orderKey;
    private int subOrder;
    private String delta;
    private Map<String, Object> payload;
    private String errorMsg;
    @JsonProperty("transient")
    private boolean transientItem;

    /**
     * 业务时间线状态。history 仍保留旧的 status 字段，同时输出统一协议名 itemStatus。
     */
    public String getItemStatus() {
        return status;
    }
}
