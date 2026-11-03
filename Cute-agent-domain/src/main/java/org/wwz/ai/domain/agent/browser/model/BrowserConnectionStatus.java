package org.wwz.ai.domain.agent.browser.model;

import lombok.Value;

/**
 * Browser relay connectivity and current tab metadata for one user.
 */
@Value
public final class BrowserConnectionStatus {

    boolean connected;
    String tabUrl;
    String tabTitle;

    public static BrowserConnectionStatus offline() {
        return new BrowserConnectionStatus(false, null, null);
    }
}
