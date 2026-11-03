package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface IToolOutputCanvasPublishDao {

    /**
     * 插入 canvas publish 行；返回 0 表示同 invocation 或 request/toolCall 已存在。
     * 插入结果不暴露数据库自增 id。
     */
    int insert(@Param("row") Map<String, Object> row);

    /** 单行查询；不存在返回 null。结果 Map 的 key 为 JDBC 列标签，id 值类型由驱动决定。 */
    Map<String, Object> queryByToolInvocationId(@Param("toolInvocationId") Long toolInvocationId);

    List<Map<String, Object>> queryByToolInvocationIds(@Param("toolInvocationIds") List<Long> toolInvocationIds);

    /** 单行查询；不存在返回 null。 */
    Map<String, Object> queryByRequestToolCall(@Param("requestId") String requestId,
                                               @Param("toolCallId") String toolCallId);
}
