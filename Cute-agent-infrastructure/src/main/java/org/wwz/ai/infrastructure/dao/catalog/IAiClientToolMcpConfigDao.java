package org.wwz.ai.infrastructure.dao.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.wwz.ai.infrastructure.dao.po.catalog.AiClientToolMcpPO;

import java.util.List;

/** Catalog 上下文 MCP 配置 DAO。 */
@Mapper
public interface IAiClientToolMcpConfigDao {
    int insert(AiClientToolMcpPO po);
    int updateById(AiClientToolMcpPO po);
    /** 按业务键更新；调用方必须保证 mcpId 非空且全表唯一。 */
    int updateByMcpId(AiClientToolMcpPO po);
    int deleteById(Long id);
    /** 按业务键删除；调用方必须保证 mcpId 非空且全表唯一。 */
    int deleteByMcpId(String mcpId);
    AiClientToolMcpPO queryById(Long id);
    AiClientToolMcpPO queryByMcpId(String mcpId);
    List<AiClientToolMcpPO> queryAll();
    List<AiClientToolMcpPO> queryByStatus(Integer status);
    List<AiClientToolMcpPO> queryByTransportType(String transportType);
    List<AiClientToolMcpPO> queryEnabled();
}
