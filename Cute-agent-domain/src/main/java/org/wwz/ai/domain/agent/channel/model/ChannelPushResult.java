package org.wwz.ai.domain.agent.channel.model;

/**
 * 一次出站的结果。
 *
 * @param success   是否成功
 * @param messageId 平台返回的消息 ID，失败时可为 null
 * @param error     失败原因，成功时为 null
 * @param retryable 是否值得重试（4xx 参数错误不重试）
 */
public record ChannelPushResult(
        boolean success,
        String messageId,
        String error,
        boolean retryable
) {

    public static ChannelPushResult ok(String messageId) {
        return new ChannelPushResult(true, messageId, null, false);
    }

    public static ChannelPushResult fail(String error, boolean retryable) {
        return new ChannelPushResult(false, null, error, retryable);
    }
}
