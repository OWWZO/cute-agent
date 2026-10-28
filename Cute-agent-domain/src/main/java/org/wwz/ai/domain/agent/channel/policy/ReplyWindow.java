package org.wwz.ai.domain.agent.channel.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * 被动回复窗口判定。
 * <p>QQ 只在收到用户消息后的一段时间内允许用该消息的 {@code msg_id} 回复；超出窗口只能发主动消息
 * （需要单独权限）。窗口确切时长官方未公开，由 {@code cute.channel.qqbot.reply-window-seconds}
 * 配置，默认值待实测校准。</p>
 *
 * @param ttl 窗口时长
 */
public record ReplyWindow(Duration ttl) {

    /** 保守默认值。Phase 0 实测后由配置覆盖。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(300);

    public ReplyWindow {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 必须为正");
        }
    }

    public static ReplyWindow ofSeconds(long seconds) {
        return new ReplyWindow(Duration.ofSeconds(seconds));
    }

    public static ReplyWindow defaults() {
        return new ReplyWindow(DEFAULT_TTL);
    }

    /** 窗口是否仍开着。正好等于 TTL 时视为已关闭（不留边界歧义）。 */
    public boolean isOpen(Instant receivedAt, Instant now) {
        if (receivedAt == null || now == null) {
            return false;
        }
        return age(receivedAt, now).compareTo(ttl) < 0;
    }

    /** 入站至今经过的时间；时钟回拨时返回零。 */
    public Duration age(Instant receivedAt, Instant now) {
        if (receivedAt == null || now == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(receivedAt, now);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
