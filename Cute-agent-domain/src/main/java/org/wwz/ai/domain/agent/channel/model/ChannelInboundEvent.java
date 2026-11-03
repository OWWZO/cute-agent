package org.wwz.ai.domain.agent.channel.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次入站消息的归一化结果。
 *
 * @param platform       平台标识
 * @param bindingId      属于哪个绑定；身份、会话、连接都按它隔离
 * @param chatType       会话形态
 * @param targetId       回复目标（C2C 为 openid，GROUP 为 group_openid）
 * @param platformUserId 发送者在该平台的身份（C2C 为 user_openid，GROUP 为 member_openid）
 * @param text           正文，已剥离平台特有的 @ 前缀
 * @param messageId      平台消息 ID，被动回复时回传
 * @param receivedAt     接收时刻；由适配器在收到平台消息时用本机时钟（{@code Instant.now()}）赋值，
 *                       仅用于被动回复窗口计算，不承担跨连接排序或去重职责（去重按 {@code messageId}）
 */
public record ChannelInboundEvent(
        String platform,
        Long bindingId,
        ChannelChatType chatType,
        String targetId,
        String platformUserId,
        String text,
        String messageId,
        Instant receivedAt
) {

    public ChannelInboundEvent {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform must not be blank");
        }
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(chatType, "chatType");
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        Objects.requireNonNull(platformUserId, "platformUserId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
