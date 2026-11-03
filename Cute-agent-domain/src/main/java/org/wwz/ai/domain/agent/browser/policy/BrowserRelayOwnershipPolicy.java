package org.wwz.ai.domain.agent.browser.policy;

import org.wwz.ai.domain.agent.browser.model.BrowserRelayConnection;
import org.wwz.ai.domain.agent.browser.model.BrowserRelayInboundMessage;

/**
 * Ownership rules for relay connections and decoded inbound messages.
 *
 * <p><b>同用户多连接语义（有意为之）</b>：连接注册表按 userId 索引，同一用户最多保留一条
 * 「当前连接」，新的注册会替换旧连接（旧连接被 terminate）。因此 {@link #owns} 只需要校验
 * 「声明的 userId == 连接自身的 userId」；跨 tab / 跨设备的同用户访问在注册层就已经收敛为
 * 单连接，不存在按 connectionId 精确寻址其它连接的入口。</p>
 * <p>连接维度的校验没有被省略：所有入站帧都必须通过 {@link #matches}（userId + connectionId）
 * 与当前连接逐项匹配，不匹配的帧直接丢弃（见 {@code BrowserRelaySocketAdapter#accept}）。</p>
 */
public final class BrowserRelayOwnershipPolicy {

    public boolean owns(String claimedUserId, BrowserRelayConnection connection) {
        return nonBlank(claimedUserId)
                && connection != null
                && claimedUserId.equals(connection.userId())
                && nonBlank(connection.id());
    }

    public boolean hasIdentity(BrowserRelayInboundMessage message) {
        return message != null
                && nonBlank(message.userId())
                && nonBlank(message.connectionId());
    }

    public boolean matches(String expectedUserId,
                           String expectedConnectionId,
                           BrowserRelayInboundMessage message) {
        return hasIdentity(message)
                && nonBlank(expectedUserId)
                && nonBlank(expectedConnectionId)
                && expectedUserId.equals(message.userId())
                && expectedConnectionId.equals(message.connectionId());
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
