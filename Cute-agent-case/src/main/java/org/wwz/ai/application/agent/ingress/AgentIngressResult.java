package org.wwz.ai.application.agent.ingress;

import lombok.Builder;
import lombok.Value;

/**
 * 入站路由的统一结果，由 Trigger 映射成对应 HTTP 响应。
 */
@Value
@Builder
public class AgentIngressResult {

    AgentIngressStatus status;
    AgentIngressMode mode;
    String sessionId;
    String requestId;
    String clientMessageId;
    String activeRequestId;
    boolean accepted;
    boolean queued;
    boolean held;
    boolean wakeScheduled;
    boolean retryable;
    boolean duplicate;
    String message;
}
