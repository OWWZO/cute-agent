package org.wwz.ai.domain.agent.browser.policy;

import org.wwz.ai.domain.agent.browser.model.BrowserAction;
import org.wwz.ai.domain.agent.browser.model.BrowserCommand;
import org.wwz.ai.domain.agent.browser.model.BrowserCommandResult;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Browser command business rules shared by the application seam and the
 * outbound relay adapter.
 */
public final class BrowserRelayCommandPolicy {

    private static final String ACTION_WAIT_DOWNLOAD = "wait-download";
    private static final String ACTION_SCREENSHOT = "screenshot";
    private static final long SCREENSHOT_CAP_MILLIS = 30_000L;
    private static final long DEFAULT_ACTION_CAP_MILLIS = 60_000L;
    private static final long MAX_WAIT_DOWNLOAD_MILLIS = 180_000L;
    private static final long MAX_WAIT_DOWNLOAD_SECONDS = 180L;

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "exec", "navigate", "tabs", "cookies", "screenshot", "close-window",
            "set-file-input", "insert-text", "bind", "network-capture-start",
            "network-capture-read", "wait-download", "cdp", "frames"
    );

    public BrowserCommandResult rejection(BrowserCommand command) {
        if (command == null || command.action() == null) {
            return BrowserCommandResult.invalid(command == null ? null : command.rpcId(),
                    "userId and action are required");
        }
        if (command.userId() == null || command.userId().isBlank()) {
            return BrowserCommandResult.invalid(command.rpcId(), "userId and action are required");
        }
        BrowserAction action = command.action();
        if (action.isLeaseRelease()) {
            return null;
        }
        if (!ALLOWED_ACTIONS.contains(action.value().toLowerCase(Locale.ROOT))) {
            return BrowserCommandResult.unsupported(command.rpcId());
        }
        return null;
    }

    public boolean isLeaseRelease(BrowserCommand command) {
        return command != null && command.action() != null && command.action().isLeaseRelease();
    }

    public BrowserCommand withEffectiveTimeout(BrowserCommand command,
                                                Duration defaultTimeout,
                                                long nowMillis) {
        Duration requested = command.timeout() == null
                ? timeoutFromParameters(command, defaultTimeout)
                : command.timeout();
        long timeoutMillis = Math.max(1L, requested == null ? 1L : requested.toMillis());
        timeoutMillis = Math.min(timeoutMillis, actionCapMillis(command));
        if (command.deadlineAt() != null && command.deadlineAt() > nowMillis) {
            timeoutMillis = Math.min(timeoutMillis, command.deadlineAt() - nowMillis);
        }
        return command.withTimeout(Duration.ofMillis(Math.max(1L, timeoutMillis)));
    }

    private long actionCapMillis(BrowserCommand command) {
        String action = command.action() == null ? "" : command.action().value();
        if (ACTION_SCREENSHOT.equals(action)) {
            return SCREENSHOT_CAP_MILLIS;
        }
        if (ACTION_WAIT_DOWNLOAD.equals(action)) {
            Long timeoutMs = positiveTimeoutMillis(command);
            return timeoutMs == null ? DEFAULT_ACTION_CAP_MILLIS : Math.min(timeoutMs, MAX_WAIT_DOWNLOAD_MILLIS);
        }
        return DEFAULT_ACTION_CAP_MILLIS;
    }

    private Duration timeoutFromParameters(BrowserCommand command, Duration defaultTimeout) {
        if (ACTION_WAIT_DOWNLOAD.equals(command.action().value())) {
            Long timeoutMs = positiveTimeoutMillis(command);
            if (timeoutMs != null) {
                long seconds = Math.min(MAX_WAIT_DOWNLOAD_SECONDS, Math.max(1L, (timeoutMs + 999L) / 1000L));
                return Duration.ofSeconds(seconds);
            }
        }
        return defaultTimeout;
    }

    private static Long positiveTimeoutMillis(BrowserCommand command) {
        Object timeoutMs = command.parameters().get("timeoutMs");
        if (timeoutMs instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return null;
    }
}
