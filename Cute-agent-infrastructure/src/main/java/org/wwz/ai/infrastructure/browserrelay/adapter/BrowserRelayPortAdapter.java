package org.wwz.ai.infrastructure.browserrelay.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.BrowserRelayPort;
import org.wwz.ai.domain.agent.adapter.port.BrowserRelaySocketPort;

/**
 * Compatibility adapter for the Agent-tool outbound BrowserRelayPort.
 *
 * <p>The outbound contract is limited to the online guard consumed by tools;
 * connection lifecycle and command transport remain on the typed socket port.</p>
 */
@Component
@ConditionalOnBean(BrowserRelaySocketPort.class)
public class BrowserRelayPortAdapter implements BrowserRelayPort {

    private final BrowserRelaySocketPort socketPort;

    public BrowserRelayPortAdapter(BrowserRelaySocketPort socketPort) {
        this.socketPort = socketPort;
    }

    @Override
    public boolean isOnline(String userId) {
        return socketPort.isOnline(userId);
    }
}
