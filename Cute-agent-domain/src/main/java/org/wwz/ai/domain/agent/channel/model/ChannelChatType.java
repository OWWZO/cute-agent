package org.wwz.ai.domain.agent.channel.model;

/**
 * 渠道会话形态。
 * <p>本枚举是纯值类型，不携带端点或键格式逻辑：出站 REST 端点由基础设施层的 QQ REST 客户端
 * 按此形态选择，会话键格式由 {@code ChannelSessionKey} 生成。新增形态时两处都要同步扩展。</p>
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
