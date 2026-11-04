package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;

import java.util.List;
import java.util.Map;

@Mapper
public interface IWorkingMemoryMessageDao {

    int batchInsertMessages(@Param("records") List<WorkingMemoryMessage> records);

    List<WorkingMemoryMessage> selectBySessionIdOrdered(@Param("sessionId") String sessionId);

    List<WorkingMemoryMessage> selectByTurnIds(@Param("turnIds") List<Long> turnIds);

    List<WorkingMemorySearchMessage> searchFullTextBySession(@Param("sessionId") String sessionId,
                                                             @Param("query") String query,
                                                             @Param("limit") int limit,
                                                             @Param("roles") List<String> roles);

    List<WorkingMemorySearchMessage> searchFullTextByUserId(@Param("userId") String userId,
                                                             @Param("query") String query,
                                                             @Param("limit") int limit,
                                                             @Param("roles") List<String> roles);

    List<WorkingMemorySearchMessage> scanBySession(@Param("sessionId") String sessionId,
                                                   @Param("query") String query,
                                                   @Param("limit") int limit,
                                                   @Param("roles") List<String> roles);

    List<WorkingMemorySearchMessage> scanByUserId(@Param("userId") String userId,
                                                   @Param("query") String query,
                                                   @Param("limit") int limit,
                                                   @Param("roles") List<String> roles);

    /**
     * 查询滚动定位锚点；消息不属于该 session、已删除或不可见时返回 null。
     */
    WorkingMemorySearchMessage selectScrollAnchor(@Param("sessionId") String sessionId,
                                                  @Param("messageId") long messageId);

    /**
     * 查询锚点之前的消息，按 turnSeq/seqNo/id 倒序取最近一页。
     * {@code roles} 为 null 或空时不加角色过滤。
     */
    List<WorkingMemorySearchMessage> selectScrollBefore(@Param("sessionId") String sessionId,
                                                        @Param("messageId") long messageId,
                                                        @Param("limit") int limit,
                                                        @Param("roles") List<String> roles);

    /**
     * 查询锚点之后的消息，按 turnSeq/seqNo/id 正序取下一页。
     * {@code roles} 为 null 或空时不加角色过滤。
     */
    List<WorkingMemorySearchMessage> selectScrollAfter(@Param("sessionId") String sessionId,
                                                       @Param("messageId") long messageId,
                                                       @Param("limit") int limit,
                                                       @Param("roles") List<String> roles);

    List<WorkingMemorySearchMessage> selectHistoryBySession(@Param("sessionId") String sessionId);

    /** 最近的主会话摘要，用于 session_search browse。 */
    List<Map<String, Object>> selectRecentSessions(@Param("userId") String userId,
                                                   @Param("limit") int limit);

    /** 批量读取会话摘要，用于 session_search discover 的轻量结果补齐。 */
    List<Map<String, Object>> selectSessionSummaries(@Param("sessionIds") List<String> sessionIds);

    Map<String, Object> selectSessionSummary(@Param("sessionId") String sessionId);
}
