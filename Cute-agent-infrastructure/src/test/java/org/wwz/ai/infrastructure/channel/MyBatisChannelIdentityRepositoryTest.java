package org.wwz.ai.infrastructure.channel;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;
import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;
import org.wwz.ai.domain.agent.channel.policy.ChannelUserId;
import org.wwz.ai.infrastructure.adapter.repository.channel.MyBatisChannelIdentityRepository;
import org.wwz.ai.infrastructure.channel.dao.IAiAgentChannelIdentityDao;
import org.wwz.ai.infrastructure.channel.dao.po.AiAgentChannelIdentityPO;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 身份 findOrCreate 并发冲突回归：唯一索引挡下重复插入后必须回查赢家，而不是把异常抛给调用方。
 */
public class MyBatisChannelIdentityRepositoryTest {

    @Test
    public void findOrCreateRecoversWhenConcurrentInsertHitsUniqueKey() {
        long bindingId = 9L;
        String platform = "qqbot";
        String platformUserId = "openid-race";
        AiAgentChannelIdentityPO winner = AiAgentChannelIdentityPO.builder()
                .id(77L)
                .bindingId(bindingId)
                .platform(platform)
                .platformUserId(platformUserId)
                .userId(ChannelUserId.of(bindingId, platformUserId))
                .firstSeenAt(LocalDateTime.now())
                .build();

        AtomicInteger queries = new AtomicInteger();
        AtomicInteger touches = new AtomicInteger();
        IAiAgentChannelIdentityDao dao = new IAiAgentChannelIdentityDao() {
            @Override
            public AiAgentChannelIdentityPO query(Long b, String p, String u) {
                // 第一次是竞争前的存在性检查（没有），冲突后回查返回赢家行。
                return queries.getAndIncrement() == 0 ? null : winner;
            }

            @Override
            public int insert(AiAgentChannelIdentityPO po) {
                throw new DuplicateKeyException("uk_channel_identity");
            }

            @Override
            public int touch(Long id, String displayName, LocalDateTime lastSeenAt) {
                touches.incrementAndGet();
                return 1;
            }
        };

        ChannelIdentity identity = new MyBatisChannelIdentityRepository(dao)
                .findOrCreate(bindingId, platform, platformUserId, "昵称");

        Assert.assertEquals(winner.getUserId(), identity.getUserId());
        Assert.assertEquals(Long.valueOf(77L), identity.getId());
        Assert.assertEquals("昵称", identity.getDisplayName());
        Assert.assertEquals(1, touches.get());
    }
}
