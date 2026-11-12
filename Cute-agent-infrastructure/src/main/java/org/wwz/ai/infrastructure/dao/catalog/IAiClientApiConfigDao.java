package org.wwz.ai.infrastructure.dao.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.catalog.AiClientApiPO;

import java.util.List;

/**
 * Catalog 上下文 API 配置 DAO。
 */
@Mapper
public interface IAiClientApiConfigDao {

    /** 插入 API 配置，并回填自增主键。 */
    int insert(AiClientApiPO po);

    /** 按主键更新 API 配置。 */
    int updateById(AiClientApiPO po);

    /** 按业务键 apiId 更新 API 配置；可能影响多行。 */
    int updateByApiId(AiClientApiPO po);

    /** 按主键物理删除 API 配置。 */
    int deleteById(@Param("id") Long id);

    /** 按业务键 apiId 物理删除 API 配置；可能影响多行。 */
    int deleteByApiId(@Param("apiId") String apiId);

    /** 按主键查询；不存在时返回 null。 */
    AiClientApiPO queryById(@Param("id") Long id);

    /** 按业务键 apiId 查询；不存在时返回 null。 */
    AiClientApiPO queryByApiId(@Param("apiId") String apiId);

    List<AiClientApiPO> queryEnabledLimit(@Param("limit") int limit);

    List<AiClientApiPO> queryPage(@Param("offset") int offset, @Param("limit") int limit);
}
