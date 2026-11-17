package org.wwz.ai.application.agent.ingress;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.agent.query.SessionPreferences;

/**
 * Trigger 到 Case 的统一入站命令；不携带 SSE、Mapper 或运行时上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"query", "gptQueryCommand"})
public class AgentIngressCommand {

    private AgentIngressSource source;
    private AgentIngressType type;
    private AgentIngressOrigin commandOrigin;
    private Integer protocolVersion;
    private String userId;
    private String sessionId;
    private String requestId;
    private String clientMessageId;
    private String idempotencyKey;
    private String query;
    private SessionPreferences preferences;
    private GptQueryCommand gptQueryCommand;
}
