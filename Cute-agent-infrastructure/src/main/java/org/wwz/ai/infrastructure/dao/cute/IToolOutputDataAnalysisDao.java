package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * data_analysis 输出表 DAO。
 *
 * <p>insert 的 Map 使用 camelCase key（toolInvocationId/runId/requestId/sessionId/toolCallId/
 * status/errorMsg/task/summary/content）；查询结果的 key 为 JDBC 列标签。</p>
 */
@Mapper
public interface IToolOutputDataAnalysisDao {

    int insert(@Param("row") Map<String, Object> row);

    Map<String, Object> queryByToolInvocationId(@Param("toolInvocationId") Long toolInvocationId);

    List<Map<String, Object>> queryByToolInvocationIds(@Param("toolInvocationIds") List<Long> toolInvocationIds);

    Map<String, Object> queryByRequestToolCall(@Param("requestId") String requestId,
                                               @Param("toolCallId") String toolCallId);
}
