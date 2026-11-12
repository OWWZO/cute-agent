package org.wwz.ai.application.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.browser.BrowserPairingRecord;
import org.wwz.ai.application.agent.browser.BrowserPairingView;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.auth.command.AuthChangePasswordCommand;
import org.wwz.ai.application.auth.command.AuthLoginCommand;
import org.wwz.ai.application.auth.command.AuthRegisterCommand;
import org.wwz.ai.application.auth.result.AuthAccountResult;
import org.wwz.ai.application.auth.result.AuthResult;
import org.wwz.ai.application.auth.result.AuthTokenResult;
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

    @Test
    public void accessTokensAreRedactedWithoutHidingSafeTokenMetadata() {
        String accessToken = "never-log-this-access-token";
        String refreshToken = "never-log-this-refresh-token";
        String issuedText = new IssuedAuthToken(
                AuthResult.success(new AuthTokenResult(
                        "Bearer", accessToken, 900,
                        new AuthAccountResult("u1", "alice", "Alice", "USER"))),
                refreshToken)
                .toString();

        assertRedacted("IssuedAuthToken.toString() 含 token", issuedText, accessToken);
        assertRedacted("IssuedAuthToken.toString() 含 refresh token", issuedText, refreshToken);
        assertContains("IssuedAuthToken.toString() 缺少非敏感字段 Bearer", issuedText, "Bearer");
        assertContains("IssuedAuthToken.toString() 缺少非敏感字段 900", issuedText, "900");
        assertContains("IssuedAuthToken.toString() 缺少非敏感字段 alice", issuedText, "alice");

        String tokenText = new AuthTokenResult("Bearer", accessToken, 900, null).toString();
        assertRedacted("AuthTokenResult.toString() 含 token", tokenText, accessToken);
        assertContains("AuthTokenResult.toString() 缺少非敏感字段 Bearer", tokenText, "Bearer");
        assertContains("AuthTokenResult.toString() 缺少非敏感字段 900", tokenText, "900");
    }

    private static void assertRedacted(String text, String secret) {
        Assert.assertFalse(text, text.contains(secret));
    }

    private static void assertRedacted(String message, String text, String secret) {
        Assert.assertFalse(message, text.contains(secret));
    }

    private static void assertContains(String message, String text, String expected) {
        Assert.assertTrue(message, text.contains(expected));
    }
}
