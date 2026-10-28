package org.wwz.ai.infrastructure.adapter.repository.channel;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;
import org.wwz.ai.domain.agent.channel.port.ChannelIdentityRepository;
import org.wwz.ai.domain.agent.channel.policy.ChannelUserId;
import org.wwz.ai.infrastructure.channel.dao.IAiAgentChannelIdentityDao;
import org.wwz.ai.infrastructure.channel.dao.po.AiAgentChannelIdentityPO;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 平台身份仓储。userId 由 (bindingId, openid) 确定性推导，首次见到时落库。
 */
public class MyBatisChannelIdentityRepository implements ChannelIdentityRepository {

    private final IAiAgentChannelIdentityDao dao;

    public MyBatisChannelIdentityRepository(IAiAgentChannelIdentityDao dao) {
        this.dao = dao;
    }

    @Override
    public Optional<ChannelIdentity> find(Long bindingId, String platform, String platformUserId) {
        return Optional.ofNullable(toDomain(
                dao.query(bindingId, platform, platformUserId)));
    }

    @Override
    public synchronized ChannelIdentity findOrCreate(Long bindingId,
                                                     String platform,
                                                     String platformUserId,
                                                     String displayName) {
        AiAgentChannelIdentityPO existing = dao.query(bindingId, platform, platformUserId);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            dao.touch(existing.getId(), displayName, now);
            existing.setLastSeenAt(now);
            if (StringUtils.isNotBlank(displayName)) {
                existing.setDisplayName(displayName);
            }
            return toDomain(existing);
        }
        AiAgentChannelIdentityPO po = AiAgentChannelIdentityPO.builder()
                .bindingId(bindingId)
                .platform(platform)
                .platformUserId(platformUserId)
                .userId(ChannelUserId.of(bindingId, platformUserId))
                .displayName(displayName)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
        dao.insert(po);
        return toDomain(po);
    }

    private ChannelIdentity toDomain(AiAgentChannelIdentityPO po) {
        if (po == null) {
            return null;
        }
        return ChannelIdentity.builder()
                .id(po.getId())
                .bindingId(po.getBindingId())
                .platform(po.getPlatform())
                .platformUserId(po.getPlatformUserId())
                .userId(po.getUserId())
                .displayName(po.getDisplayName())
                .firstSeenAt(po.getFirstSeenAt())
                .lastSeenAt(po.getLastSeenAt())
                .build();
    }
}
