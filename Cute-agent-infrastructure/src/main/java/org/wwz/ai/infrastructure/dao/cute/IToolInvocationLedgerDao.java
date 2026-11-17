package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;

import java.util.List;

/**
 * 工具调用账本 DAO。
 */
@Mapper
public interface IToolInvocationLedgerDao {

    int insertToolInvocation(ToolInvocation invocation);

    /**
     * 单条多值 INSERT；生成的主键按顺序回填到每个参数对象的 id。
     * <p>参数刻意不加 {@code @Param}：MyBatis 只有在集合作为根参数时才保证
     * {@code useGeneratedKeys} 的 keyProperty 逐个回填到列表元素。</p>
     */
    int batchInsertToolInvocations(List<ToolInvocation> invocations);

    int updateToolInvocationFinish(ToolInvocation invocation);

    ToolInvocation queryById(@Param("id") Long id);

    ToolInvocation queryByRunIdAndToolCallId(@Param("runId") Long runId,
                                             @Param("toolCallId") String toolCallId);

    List<ToolInvocation> queryByRunId(@Param("runId") Long runId);

    List<ToolInvocation> queryByRunIds(@Param("runIds") List<Long> runIds);

    List<ToolInvocation> queryByLlmInvocationIds(@Param("llmInvocationIds") List<Long> llmInvocationIds);

    /**
     * 按用户 + 工具名读取最近调用；{@code user_id} 条件下推到 SQL，禁止返回全量用户数据。
     */
    List<ToolInvocationView> queryRecentByToolName(@Param("userId") String userId,
                                                   @Param("toolName") String toolName,
                                                   @Param("limit") int limit);

    /**
     * 启动对账：扫「父 run 已终态、自身仍 running 且未结束」的工具孤儿。
     * <p>
     * 典型来源是 {@code run_in_background} 的后台子 Agent：父回合已结束并落终态，
     * 但 {@code persistLedgerSettlement} 因进程被杀从未被调到，工具行永远 running。
     * 父 run 仍 RUNNING 的工具不在此列（可能只是本轮尚未收尾），避免误伤。
     */
    List<ToolInvocation> queryOrphanedRunningToolInvocations(@Param("startedBefore") java.time.LocalDateTime startedBefore,
                                                             @Param("limit") int limit);
}
