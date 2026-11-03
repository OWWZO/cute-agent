package org.wwz.ai.infrastructure.adapter.port;

import org.junit.Assert;
import org.junit.Test;

public class AgentBrowserCredentialRedactorTest {

    @Test
    public void redactsCredentialsAfterAmpersandInNamedEndpoint() {
        String secret = "SECRET_AFTER_AMPERSAND";

        String redacted = AgentBrowserCredentialRedactor.redact(
                "cdp_ws_url=wss://host/devtools/browser/x?jwt=abc&access_token=" + secret, null);

        Assert.assertFalse(redacted, redacted.contains(secret));
        Assert.assertFalse(redacted, redacted.contains("jwt=abc"));
    }

    @Test
    public void redactsApiKeysAndBearerTokensWithoutCallerSuppliedKey() {
        String redacted = AgentBrowserCredentialRedactor.redact(
                "{\"api_key\":\"sk-json-secret\"} apiKey=sk-query-secret "
                        + "Authorization: Bearer bearer-secret",
                null);

        Assert.assertFalse(redacted, redacted.contains("sk-json-secret"));
        Assert.assertFalse(redacted, redacted.contains("sk-query-secret"));
        Assert.assertFalse(redacted, redacted.contains("bearer-secret"));
    }
}
