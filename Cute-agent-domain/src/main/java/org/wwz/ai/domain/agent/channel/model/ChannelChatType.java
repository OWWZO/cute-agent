package org.wwz.ai.domain.agent.channel.model;

/**
 * 渠道会话形态，决定出站 REST 端点与会话键格式。
 */
public enum ChannelChatType {

    /** 私聊。QQ 走 {@code /v2/users/{openid}/messages}。 */
    C2C,

    /** 群聊。QQ 需 @ 机器人，走 {@code /v2/groups/{group_openid}/messages}。 */
    GROUP,

    /** 频道。本期不实现。 */
    GUILD,

    /** 频道私信。本期不实现。 */
    DM
}
