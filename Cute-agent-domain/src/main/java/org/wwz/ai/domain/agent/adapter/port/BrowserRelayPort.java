package org.wwz.ai.domain.agent.adapter.port;

/**
 * Outbound Browser Relay capability consumed by Agent tools.
 *
 * <p>Only the online guard is part of the consumed contract. Command execution
 * and connection lifecycle stay on the inbound {@link BrowserRelaySocketPort},
 * so the same relay semantics are not re-declared here with a second set of
 * result types.</p>
 */
public interface BrowserRelayPort {

    boolean isOnline(String userId);
}
