package org.wwz.ai.infrastructure.channel.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.channel.dao.po.AiAgentChannelBindingPO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ai_agent_channel_binding 数据访问。
 */
@Mapper
public interface IAiAgentChannelBindingDao {

    AiAgentChannelBindingPO queryById(@Param("id") Long id);

    AiAgentChannelBindingPO queryByOwner(@Param("ownerUserId") String ownerUserId,
                                         @Param("platform") String platform);

    AiAgentChannelBindingPO queryByAppId(@Param("platform") String platform,
                                         @Param("appId") String appId);

    List<AiAgentChannelBindingPO> queryByStatus(@Param("status") String status,
                                                @Param("limit") int limit);

    long countAll();

    int insert(AiAgentChannelBindingPO po);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("lastError") String lastError,
                     @Param("connectedAt") LocalDateTime connectedAt);

    int disable(@Param("id") Long id);
}
