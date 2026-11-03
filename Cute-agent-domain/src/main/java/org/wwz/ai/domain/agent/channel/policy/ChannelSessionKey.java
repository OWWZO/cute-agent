package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;

/**
 * 渠道会话键：把「会话形态 + 目标 + 发送者」映射成稳定的 sessionId。
 * <p>不加租户前缀——QQ 的 openid 按 bot 隔离，同一人在不同 bot 下 openid 不同，天然唯一。</p>
 * <p><b>当前只有 QQ 一个渠道</b>，键前缀 {@code qq_} 是硬编码的，键本身不含平台维度。
 * 新增其它渠道（微信/飞书等）时必须同时扩展键格式并保持对存量 {@code qq_} 会话的兼容，
 * 否则不同平台的同值 id 会命中同一个 sessionId。</p>
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

    /**
     * 从会话键反解会话形态；非渠道会话返回 {@code null}。
     */
    public static ChannelChatType chatTypeOf(String sessionId) {
        if (StringUtils.isBlank(sessionId) || !sessionId.startsWith("qq_")) {
            return null;
        }
        if (sessionId.startsWith("qq_c2c_")) {
            return ChannelChatType.C2C;
        }
        if (sessionId.startsWith("qq_group_")) {
            return ChannelChatType.GROUP;
        }
        if (sessionId.startsWith("qq_guild_")) {
            return ChannelChatType.GUILD;
        }
        if (sessionId.startsWith("qq_dm_")) {
            return ChannelChatType.DM;
        }
        return null;
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
