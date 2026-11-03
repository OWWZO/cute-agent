package org.wwz.ai.domain.agent.adapter.port;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class AgentBrowserSessionStatus {

    boolean exists;

    String agentSessionId;

    LocalDateTime lastUsedAt;

    boolean reconstructed;

    /**
     * No resumable Agent Browser session exists for the current user.
     */
    public static AgentBrowserSessionStatus absent() {
        return AgentBrowserSessionStatus.builder()
                .exists(false)
                .reconstructed(false)
                .build();
    }

    /**
     * A session mapping exists. {@code agentSessionId} may be null because legacy rows
     * are allowed to omit it.
     */
    public static AgentBrowserSessionStatus present(String agentSessionId, LocalDateTime lastUsedAt) {
        return AgentBrowserSessionStatus.builder()
                .exists(true)
                .agentSessionId(agentSessionId)
                .lastUsedAt(lastUsedAt)
                .reconstructed(false)
                .build();
    }
}
