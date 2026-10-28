package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;

/**
 * 渠道会话键：把「会话形态 + 目标 + 发送者」映射成稳定的 sessionId。
 * <p>不加租户前缀——QQ 的 openid 按 bot 隔离，同一人在不同 bot 下 openid 不同，天然唯一。</p>
 */
public final class ChannelSessionKey {

    /** 与 {@code ai_agent_dialogue_session.session_id} 的列宽一致。 */
    public static final int MAX_LENGTH = 128;

    private ChannelSessionKey() {
    }

    public static String of(ChannelChatType chatType, String targetId, String platformUserId) {
        if (chatType == null) {
            throw new IllegalArgumentException("chatType 不能为空");
        }
        String target = require(targetId, "targetId");
        return switch (chatType) {
            case C2C -> "qq_c2c_" + target;
            case GROUP -> "qq_group_" + target + "_" + require(platformUserId, "platformUserId");
            case GUILD -> "qq_guild_" + target;
            case DM -> "qq_dm_" + target;
        };
    }

    private static String require(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(field + " 过长：" + trimmed.length());
        }
        return trimmed;
    }
}
