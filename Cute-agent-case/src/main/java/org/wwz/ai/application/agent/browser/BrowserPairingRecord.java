package org.wwz.ai.application.agent.browser;

import lombok.Builder;
import lombok.Value;

/**
 * 浏览器 relay 配对记录。
 * <p>{@code token}、{@code code} 与 {@code relayUrl} 是正常配对流程的必要字段；
 * builder 当前不主动校验空值，生产调用方必须保证记录完整。</p>
 */
@Value
@Builder
public class BrowserPairingRecord {

    String userId;
    String token;
    String code;
    String relayUrl;

    /** 配对短码过期时间，Unix epoch 毫秒（UTC 时间轴），应与 {@link System#currentTimeMillis()} 比较。 */
    long codeExpiresAt;
}
