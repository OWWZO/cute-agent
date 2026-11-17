package org.wwz.ai.trigger.http.agent.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent SSE/history 对外响应模型。只存在于 Trigger，不进入 Case/Domain 执行链。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamResponseVO {

    private String protocolVersion;
    private String eventType;
    private String eventId;
    private long seq;
    private String runId;
    private String itemId;
    private String parentItemId;
    private String agentId;
    private String kind;
    private String phase;
    private String itemStatus;
    private long orderKey;
    private int subOrder;
    private String delta;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> payload;
    @JsonProperty("transient")
    private boolean transientItem;

    private String status;
    private String response;
    private String responseAll;
    private boolean finished;
    private long useTimes;
    private long useTokens;
    private Map<String, Object> resultMap;
    private String responseType;
    private String traceId;
    private String reqId;
    private boolean encrypted;
    private String query;
    private List<String> messages;
    private String packageType;
    private String errorMsg;
    private long eventSeq;
    private Long retryMs;
}
