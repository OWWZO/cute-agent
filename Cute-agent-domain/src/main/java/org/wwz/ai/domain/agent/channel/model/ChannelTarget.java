package org.wwz.ai.domain.agent.channel.model;

import java.time.Instant;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;

/**
 * 一次出站的目标。
 *
 * @param platform    平台标识，与 {@link ChannelAdapter#platform()} 一致
 * @param bindingId   用哪个绑定的凭证发送；多租户下决定 appId / appSecret / WS 连接
 * @param chatType    会话形态，决定 REST 端点
 * @param targetId    C2C 为 user_openid；GROUP 为 group_openid；GUILD 为 channel_id；DM 为 guild_id
 * @param lastMsgId   最近入站消息 ID。非空时走被动回复（QQ 的 {@code msg_id}），为空表示主动消息
 * @param receivedAt  入站时刻，用于判定被动回复窗口是否仍开着
 */
public record ChannelTarget(
        String platform,
        Long bindingId,
        ChannelChatType chatType,
        String targetId,
        String lastMsgId,
        Instant receivedAt
) {

    /** 同一目标不带 msg_id 的主动消息形态，用于窗口已关闭或非回复场景。 */
    public ChannelTarget withoutReply() {
        return new ChannelTarget(platform, bindingId, chatType, targetId, null, receivedAt);
    }
}
