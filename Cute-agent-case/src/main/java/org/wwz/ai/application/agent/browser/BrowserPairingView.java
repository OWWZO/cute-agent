package org.wwz.ai.application.agent.browser;

import lombok.Builder;
import lombok.Value;

/**
 * 浏览器配对结果视图。
 * <p>该视图包含 bearer token 与一次性配对短码，均属于敏感凭据。Lombok {@code @Value}
 * 会把全部字段纳入生成的 {@code toString()}，因此禁止把整个对象写入日志或异常信息。</p>
 */
@Value
@Builder
public class BrowserPairingView {

    /** 64 位十六进制 bearer token；正常生产路径非 null，用于 relay 鉴权与重连。 */
    String token;

    /** 6 位十进制配对短码；用于人工输入或写入 bsk 配对链接 fragment，过期时间见 {@link #expiresAt}。 */
    String code;

    /** relay WebSocket 地址，格式为 {@code ws(s)://host/path}；正常生产路径非 null。 */
    String relayUrl;

    /**
     * 配对短码的过期时间，Unix epoch 毫秒（UTC 时间轴），应与
     * {@link System#currentTimeMillis()} 比较，而不是当作相对 TTL 或秒级时间戳。
     * <p>该字段不用于判断 token 是否过期；token 的生命周期由配对记录、轮换和断开操作控制。</p>
     */
    long expiresAt;

    /**
     * bsk 扩展的配对链接（021）：{@code ws(s)://host/api/agent/browser/bsk-relay#<credential>}。
     * 当前由触发层使用 6 位配对短码作为 fragment；扩展侧粘贴/接收后自动剥 fragment，
     * 后续经 WS 子协议头 {@code bsk-auth.<token>} 发送长 token。
     * <p>为 {@code null} 表示本次配对未生成 bsk 链接（网关未启用）。该 URL 的 fragment
     * 含敏感凭据，禁止完整打印到日志。</p>
     */
    String bskPairingUrl;
}
