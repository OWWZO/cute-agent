package org.wwz.ai.domain.agent.browser;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.browser.model.BrowserCommand;
import org.wwz.ai.domain.agent.browser.model.BrowserCommandResult;
import org.wwz.ai.domain.agent.browser.model.BrowserRelayInboundMessage;
import org.wwz.ai.domain.agent.browser.model.BrowserTabMetadata;
import org.wwz.ai.domain.agent.browser.policy.BrowserRelayCommandPolicy;

import java.time.Duration;
import java.util.Map;

/**
 * 浏览器协议模型的空值边界：无效输入应被判定为 invalid，而不是在构造期抛 NPE。
 */
public class BrowserModelNullSafetyTest {

    private final BrowserRelayCommandPolicy policy = new BrowserRelayCommandPolicy();

    @Test
    public void nullActionShouldBecomeInvalidCommandInsteadOfThrowing() {
        BrowserCommand command = BrowserCommand.of("user-1", null, Map.of(), Duration.ofSeconds(1));

        Assert.assertNull(command.action());
        Assert.assertEquals("invalid_body", policy.rejection(command).getErrorCode());
    }

    @Test
    public void blankActionShouldBecomeInvalidCommandInsteadOfThrowing() {
        BrowserCommand command = BrowserCommand.of("user-1", "   ", Map.of(), Duration.ofSeconds(1));

        Assert.assertNull(command.action());
        Assert.assertEquals("invalid_body", policy.rejection(command).getErrorCode());
    }

    @Test
    public void effectiveTimeoutShouldPassThroughInvalidCommands() {
        Assert.assertNull(policy.withEffectiveTimeout(null, Duration.ofSeconds(30), 1L));

        BrowserCommand noAction = new BrowserCommand("user-1", null, null, Map.of(), null, null);
        Assert.assertSame(noAction, policy.withEffectiveTimeout(noAction, Duration.ofSeconds(30), 1L));
    }

    @Test
    public void inboundMessageShouldRejectBlankIdentity() {
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "   ", "conn-1", BrowserRelayInboundMessage.Kind.HEARTBEAT, null, null));
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "  ", BrowserRelayInboundMessage.Kind.HEARTBEAT, null, null));
    }

    @Test
    public void inboundMessageShouldRequirePayloadMatchingKind() {
        BrowserCommandResult result = BrowserCommandResult.success("rpc-1", Map.of());
        BrowserTabMetadata metadata = new BrowserTabMetadata("https://example.com", "example");

        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.COMMAND_RESULT, null, null));
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.TAB_CHANGED, null, null));
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.HEARTBEAT, result, null));
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.HEARTBEAT, null, metadata));
        assertIllegalArgument(() -> new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.TAB_CHANGED, result, metadata));

        // COMMAND_RESULT 允许附带 tabMetadata（解码器会从 data 中提取），不能误判。
        BrowserRelayInboundMessage commandResult = new BrowserRelayInboundMessage(
                "user-1", "conn-1", BrowserRelayInboundMessage.Kind.COMMAND_RESULT, result, metadata);
        Assert.assertEquals(result, commandResult.commandResult());
        Assert.assertEquals(metadata, commandResult.tabMetadata());
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
