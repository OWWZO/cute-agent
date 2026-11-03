package org.wwz.ai.infrastructure.dao.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.catalog.AiClientModelPO;

import java.util.List;

/** Catalog 上下文模型配置 DAO。 */
@Mapper
public interface IAiClientModelConfigDao {
    int insert(AiClientModelPO po);
    int updateById(AiClientModelPO po);
    int updateByModelId(AiClientModelPO po);
    int deleteById(Long id);
    int deleteByModelId(String modelId);
    AiClientModelPO queryById(Long id);
    AiClientModelPO queryByModelId(String modelId);
    List<AiClientModelPO> queryAllByModelId(String modelId);
    List<AiClientModelPO> queryByApiId(String apiId);
    List<AiClientModelPO> queryByModelType(String modelType);
    List<AiClientModelPO> queryEnabled();
    List<AiClientModelPO> queryEnabledLimit(@Param("limit") int limit);
    List<AiClientModelPO> queryAll();
    List<AiClientModelPO> queryPage(@Param("offset") int offset, @Param("limit") int limit);
}
