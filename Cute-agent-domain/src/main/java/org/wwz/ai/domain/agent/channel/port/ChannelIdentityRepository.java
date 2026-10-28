package org.wwz.ai.domain.agent.channel.port;

import java.util.Optional;

import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;

/**
 * 平台身份持久化端口。
 */
public interface ChannelIdentityRepository {

    Optional<ChannelIdentity> find(Long bindingId, String platform, String platformUserId);

    /** 已存在则刷新 lastSeenAt 并返回；不存在则按确定性规则生成 userId 后插入。 */
    ChannelIdentity findOrCreate(Long bindingId, String platform, String platformUserId, String displayName);
}
