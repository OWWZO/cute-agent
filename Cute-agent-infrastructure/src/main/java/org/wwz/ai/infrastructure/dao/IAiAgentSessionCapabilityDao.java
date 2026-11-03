package org.wwz.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.AiAgentSessionCapability;

import java.util.List;

@Mapper
public interface IAiAgentSessionCapabilityDao {

    /** 按 session 查询能力覆盖项；结果顺序由数据库决定，不提供稳定排序保证。 */
    List<AiAgentSessionCapability> listBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按 {@code (session_id, kind, ref_id)} 唯一键执行 upsert，并更新 enabled。
     *
     * <p>返回值是 JDBC 更新计数：依赖目标表的唯一约束识别更新。调用方必须保证三个键字段非空。</p>
     */
    int upsert(AiAgentSessionCapability row);
}
