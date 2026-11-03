package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * image_generation_tool 输出表 DAO。
 *
 * <p>insert 的 Map 使用 camelCase key；查询结果的 key 为 JDBC 列标签。
 * 单行查询使用 LIMIT 1，未命中时返回 null。</p>
 */
@Mapper
public interface IToolOutputImageGenerationDao {

    int insert(@Param("row") Map<String, Object> row);

    Map<String, Object> queryByToolInvocationId(@Param("toolInvocationId") Long toolInvocationId);

    List<Map<String, Object>> queryByToolInvocationIds(@Param("toolInvocationIds") List<Long> toolInvocationIds);

    Map<String, Object> queryByRequestToolCall(@Param("requestId") String requestId,
                                               @Param("toolCallId") String toolCallId);

    int countByRequestSource(@Param("requestSource") String requestSource);

    List<Map<String, Object>> queryPageByRequestSource(@Param("requestSource") String requestSource,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);
}
