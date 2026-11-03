package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;

import java.util.List;

/**
 * 产物账本 DAO。
 */
@Mapper
public interface IArtifactLedgerDao {

    /**
     * 批量写入产物账本。
     *
     * <p>当前 mapper 使用 {@code INSERT IGNORE ... VALUES <foreach>}，调用方必须传入至少一条记录；
     * 返回实际插入行数。</p>
     */
    int batchInsertArtifacts(@Param("records") List<ArtifactRecord> records);

    /** 查询单 run 的原始产物行，不做子 Agent 血缘折叠。 */
    List<ArtifactRecord> queryByRunId(@Param("runId") Long runId);

    /**
     * 子 Agent 产物按血缘重键到父 Agent 卡 invocation 的单 run 查询。
     */
    List<ArtifactRecord> queryByRunIdWithSubagentFold(@Param("runId") Long runId);

    List<ArtifactRecord> queryByRunIds(@Param("runIds") List<Long> runIds);

    /**
     * 子 Agent 产物按血缘重键到父 Agent 卡 invocation 的批量查询。
     */
    List<ArtifactRecord> queryByRunIdsWithSubagentFold(@Param("runIds") List<Long> runIds);

    List<ArtifactRecord> queryBySessionId(@Param("sessionId") String sessionId);

    List<ArtifactRecord> queryBySessionIdLimit(@Param("sessionId") String sessionId,
                                               @Param("limit") int limit);
    /** 与 {@link #queryBySessionId(String)} 同口径，但由 SQL LIMIT 强制截断。 */
    List<ArtifactRecord> queryBySessionIdWithLimit(@Param("sessionId") String sessionId,
                                                   @Param("limit") int limit);

    List<ArtifactRecord> queryByToolInvocationIds(@Param("toolInvocationIds") List<Long> toolInvocationIds);

    List<ArtifactRecord> queryInputArtifactsByRunIds(@Param("runIds") List<Long> runIds);

    List<ArtifactRecord> queryOutputArtifactsByToolInvocationId(@Param("toolInvocationId") Long toolInvocationId);

    List<ArtifactRecord> queryOutputArtifactsByRunIdAndToolCallId(@Param("runId") Long runId,
                                                                  @Param("toolCallId") String toolCallId);

    List<ArtifactRecord> queryOutputArtifactsByRequestIdAndToolCallId(@Param("requestId") String requestId,
                                                                      @Param("toolCallId") String toolCallId);
}
