package org.wwz.ai.application.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.browser.BrowserPairingRecord;
import org.wwz.ai.application.agent.browser.BrowserPairingView;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.auth.command.AuthChangePasswordCommand;
import org.wwz.ai.application.auth.command.AuthLoginCommand;
import org.wwz.ai.application.auth.command.AuthRegisterCommand;
import org.wwz.ai.application.auth.result.IssuedAuthToken;
import org.wwz.ai.application.catalog.api.ApiConfigCommand;
import org.wwz.ai.application.catalog.model.CatalogModelCreateCommand;

public class SensitiveValueToStringTest {

    @Test
    public void credentialsAndUserContentAreRedactedFromToString() {
        String secret = "never-log-this-secret";

        assertRedacted(new AuthLoginCommand("alice", null, secret).toString(), secret);
        assertRedacted(new AuthRegisterCommand("alice", secret, "Alice").toString(), secret);
        assertRedacted(new AuthChangePasswordCommand("user-1", "session-1", secret, secret).toString(), secret);
        assertRedacted(new IssuedAuthToken(null, secret).toString(), secret);
        assertRedacted(new ApiConfigCommand(1L, "api-1", "https://example.test", secret, null, null, 1)
                .toString(), secret);
        assertRedacted(new CatalogModelCreateCommand(
                "api-1", "https://example.test", secret, null, null,
                "model-1", "Model", "chat", "chat", 0, 8192, 1).toString(), secret);
        assertRedacted(GptQueryCommand.builder()
                .query(secret)
                .user(secret)
                .build()
                .toString(), secret);
        assertRedacted(BrowserPairingRecord.builder()
                .userId("user-1")
                .token(secret)
                .code(secret)
                .relayUrl("wss://relay.example.test")
                .codeExpiresAt(1L)
                .build()
                .toString(), secret);
        assertRedacted(BrowserPairingView.builder()
                .token(secret)
                .code(secret)
                .relayUrl("wss://relay.example.test")
                .expiresAt(1L)
                .bskPairingUrl("wss://relay.example.test#secret")
                .build()
                .toString(), secret);
    }

    private static void assertRedacted(String text, String secret) {
        Assert.assertFalse(text, text.contains(secret));
    }
}
