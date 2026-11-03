package org.wwz.ai.infrastructure.browserrelay.transport;

import org.wwz.ai.domain.agent.browser.model.BrowserRelayConnection;

/**
 * Infrastructure-only registry for live relay channels.
 *
 * <p>Implementations are accessed concurrently by relay I/O and lifecycle threads.
 * {@link #replace}, {@link #current}, and {@link #remove} must be thread-safe;
 * {@code replace} and {@code remove} must perform a single atomic map operation
 * with respect to {@code current}.</p>
 */
public interface BrowserRelayConnectionRegistry {

    /**
     * Replaces the current connection for the supplied user.
     *
     * @param connection non-null connection to install
     * @return the displaced connection, or {@code null} when the user had none
     */
    BrowserRelayConnection replace(BrowserRelayConnection connection);

    /**
     * Returns the current connection for the user.
     *
     * @return the current point-in-time connection, or {@code null} when the user is offline
     */
    BrowserRelayConnection current(String userId);

    /**
     * Conditionally removes a connection only when it is still the current entry for the user.
     *
     * @return the removed connection, or {@code null} when no entry exists or its id differs
     */
    BrowserRelayConnection remove(String userId, String connectionId);
}
