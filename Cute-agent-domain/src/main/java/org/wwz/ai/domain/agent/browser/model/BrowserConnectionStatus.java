package org.wwz.ai.domain.agent.browser.model;

import lombok.Value;

/**
 * Browser relay connectivity and current tab metadata for one user.
 */
@Value
public final class BrowserConnectionStatus {

    boolean connected;
    /** 当前标签页 URL；未连接或尚未上报标签页元数据时为 null。 */
    String tabUrl;

    /** 当前标签页标题；未连接或尚未上报标签页元数据时为 null。 */
    String tabTitle;

    public static BrowserConnectionStatus offline() {
        return new BrowserConnectionStatus(false, null, null);
    }
}
