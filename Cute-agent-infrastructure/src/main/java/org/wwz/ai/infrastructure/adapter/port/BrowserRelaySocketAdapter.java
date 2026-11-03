package org.wwz.ai.infrastructure.adapter.port;

import org.wwz.ai.types.agent.config.BrowserRelayProperties;

/**
 * Source-compatible name for tests and integrations using the previous
 * package. The implementation lives in the Browser Relay context package.
 *
 * @deprecated Use
 *             {@link org.wwz.ai.infrastructure.browserrelay.adapter.BrowserRelaySocketAdapter}
 *             instead.
 */
@Deprecated
public class BrowserRelaySocketAdapter
        extends org.wwz.ai.infrastructure.browserrelay.adapter.BrowserRelaySocketAdapter {

    public BrowserRelaySocketAdapter(BrowserRelayProperties properties) {
        super(properties);
    }
}
