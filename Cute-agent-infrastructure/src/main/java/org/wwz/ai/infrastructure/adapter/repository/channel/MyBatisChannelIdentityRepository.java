package org.wwz.ai.infrastructure.adapter.repository.channel;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            dao.insert(po);
            return toDomain(po);
        } catch (DataIntegrityViolationException conflict) {
            // 跨实例并发首次见同一 platform 用户：唯一索引挡下后回查赢家，不能把异常抛给调用方。
            AiAgentChannelIdentityPO winner = dao.query(bindingId, platform, platformUserId);
            if (winner == null) {
                throw conflict;
            }
            dao.touch(winner.getId(), displayName, now);
            winner.setLastSeenAt(now);
            if (StringUtils.isNotBlank(displayName)) {
                winner.setDisplayName(displayName);
            }
            return toDomain(winner);
        }
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
