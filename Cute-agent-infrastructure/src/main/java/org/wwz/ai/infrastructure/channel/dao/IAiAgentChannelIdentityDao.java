package org.wwz.ai.infrastructure.channel.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.channel.dao.po.AiAgentChannelIdentityPO;

import java.time.LocalDateTime;

/**
 * ai_agent_channel_identity 数据访问。
 */
@Mapper
public interface IAiAgentChannelIdentityDao {

    AiAgentChannelIdentityPO query(@Param("bindingId") Long bindingId,
                                   @Param("platform") String platform,
                                   @Param("platformUserId") String platformUserId);

    int insert(AiAgentChannelIdentityPO po);

    int touch(@Param("id") Long id,
              @Param("displayName") String displayName,
              @Param("lastSeenAt") LocalDateTime lastSeenAt);
}
