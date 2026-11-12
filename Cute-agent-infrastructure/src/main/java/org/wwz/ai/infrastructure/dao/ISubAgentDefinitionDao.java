package org.wwz.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.SubAgentDefinitionPO;

import java.util.List;

/**
 * 可配置子 Agent 定义 DAO。
 */
@Mapper
public interface ISubAgentDefinitionDao {

    /** 查询启用且未软删的子 Agent。 */
    List<SubAgentDefinitionPO> queryEnabled();

    /** 分页查询未软删的子 Agent。 */
    List<SubAgentDefinitionPO> queryPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 按 agentKey 查询未软删的一条定义。 */
    SubAgentDefinitionPO queryByAgentKey(@Param("agentKey") String agentKey);

    int insert(SubAgentDefinitionPO po);

    /** 按 agentKey 更新未软删的定义，并保持 deleted=0。 */
    int updateByAgentKey(SubAgentDefinitionPO po);

    /** 按 agentKey 软删定义。 */
    int softDeleteByAgentKey(@Param("agentKey") String agentKey);
}
