package org.wwz.ai.domain.agent.browser.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A normalized action understood by the browser relay use case.
 */
public record BrowserAction(String value) {

    public static final String LEASE_RELEASE = "lease-release";

    public BrowserAction {
        value = Objects.requireNonNull(value, "action").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }

    public static BrowserAction of(String value) {
        return new BrowserAction(value);
    }

    public boolean isLeaseRelease() {
        return LEASE_RELEASE.equals(value);
    }
}
