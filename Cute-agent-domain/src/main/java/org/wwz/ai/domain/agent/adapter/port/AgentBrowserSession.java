package org.wwz.ai.domain.agent.adapter.port;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class AgentBrowserSession {

    String userId;

    String agentSessionId;

    String agentBrowserName;

    /** CDP WebSocket URL 通常内嵌鉴权 token，禁止进入 toString / equals / hashCode。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    String cdpWsUrl;

    boolean reconstructed;
}
