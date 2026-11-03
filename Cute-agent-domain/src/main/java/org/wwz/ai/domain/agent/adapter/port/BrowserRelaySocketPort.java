package org.wwz.ai.domain.agent.adapter.port;

import org.wwz.ai.domain.agent.browser.model.BrowserCommand;
import org.wwz.ai.domain.agent.browser.model.BrowserCommandResult;
import org.wwz.ai.domain.agent.browser.model.BrowserConnectionStatus;
import org.wwz.ai.domain.agent.browser.model.BrowserRelayConnection;
import org.wwz.ai.domain.agent.browser.model.BrowserRelayInboundMessage;

/**
 * Inbound Browser Relay application port.
 *
 * <p>The Trigger reaches this port through
 * {@code BrowserRelayApplicationService}. It receives typed commands and
 * decoded messages only; WebSocket sessions and JSON frames stay at the
 * protocol boundary.</p>
 *
 * <p>{@link #register} and {@link #unregister} are invoked from concurrent
 * WebSocket open/close callbacks and heartbeats; implementations must keep the
 * per-user connection registry thread-safe.</p>
 */
public interface BrowserRelaySocketPort {

    /**
     * Registers a live connection.
     *
     * <p>Connections whose {@code userId} or {@code connection.id()} is blank are ignored
     * without throwing. Registering a new connection for the same user replaces and terminates
     * the previous one, and fails its pending commands with {@code browser_offline}.</p>
     */
    void register(BrowserRelayConnection connection);

    /**
     * Removes the given connection.
     *
     * <p>A blank or non-matching {@code userId}/{@code connectionId} pair is a no-op; pending
     * commands bound to the removed connection fail with {@code browser_offline}.</p>
     */
    void unregister(String userId, String connectionId);

    /**
     * Accepts a decoded inbound frame.
     *
     * <p>Null messages, frames without a usable {@code userId}/{@code connectionId}, frames
     * from a connection that is no longer current, and frames carrying an unknown {@code rpcId}
     * are silently dropped; malformed input never propagates as an exception.</p>
     */
    void accept(BrowserRelayInboundMessage message);

    /**
     * @param userId may be null; a null user is reported as offline
     */
    boolean isOnline(String userId);

    /**
     * Never returns null; an offline user (including a null {@code userId}) yields a
     * disconnected status with null {@code tabUrl} / {@code tabTitle}.
     */
    BrowserConnectionStatus status(String userId);

    /**
     * Executes a command synchronously.
     *
     * <p>This method blocks the calling thread until the {@code COMMAND_RESULT} arrives, the
     * deadline elapses, or the connection drops. The effective wait is
     * {@code min(command.timeout(), command.deadlineAt() - now)}, further capped by the relay
     * RPC timeout and the per-action limits. Failures are returned as
     * {@link BrowserCommandResult} states instead of being thrown: a null command yields
     * {@code invalid}, an offline user yields {@code offline}, a timeout yields
     * {@code unknown}, a send failure yields {@code rpcFailed}, and a duplicate
     * {@code rpcId} yields {@code duplicate}.</p>
     */
    BrowserCommandResult execute(BrowserCommand command);

    /**
     * Disconnects the user's current connection.
     *
     * <p>A blank {@code userId} or an absent connection is a no-op. The method is idempotent
     * and may run concurrently with {@link #execute(BrowserCommand)}; the in-flight command
     * then fails with {@code browser_offline}.</p>
     */
    void disconnect(String userId);
}
