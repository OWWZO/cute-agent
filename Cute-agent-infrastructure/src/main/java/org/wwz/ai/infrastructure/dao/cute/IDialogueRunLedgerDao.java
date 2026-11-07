package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话执行总账 DAO。
 */
@Mapper
public interface IDialogueRunLedgerDao {

    int insertRun(DialogueRun run);

    int updateRunFinish(DialogueRun run);

    DialogueRun queryByRequestId(@Param("requestId") String requestId);

    /**
     * 只读取会话列表所需的摘要列；queryText/finalSummaryText 最长 241 字符，
     * token、错误、updated/deleted 等未投影字段保持 null。
     */
    DialogueRun querySummaryByRequestId(@Param("requestId") String requestId);

    List<DialogueRunView> queryRecentBySessionId(@Param("sessionId") String sessionId,
                                                 @Param("limit") int limit);

    List<DialogueRunView> queryBySessionId(@Param("sessionId") String sessionId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    /**
     * 运维导出专用 keyset 查询。状态为空时返回全部状态，游标为空时从窗口起点开始。
     */
    List<DialogueRunView> queryByTimeRangeAfter(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("statuses") List<Integer> statuses,
            @Param("afterCreateTime") LocalDateTime afterCreateTime,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    /**
     * 按 {@code (create_time, id)} keyset 分页。
     *
     * <p>两个游标同时为 null 时返回首页；否则必须同时提供，并按
     * {@code create_time ASC, id ASC} 稳定排序和元组比较。</p>
     */
    List<DialogueRunView> queryBySessionIdAfter(@Param("sessionId") String sessionId,
                                                @Param("afterCreateTime") java.time.LocalDateTime afterCreateTime,
                                                @Param("afterId") Long afterId,
                                                @Param("limit") int limit);

    /**
     * 批量读取每个 session 最早一条非空 query，只填 {@code sessionId/queryText}，
     * 供会话列表恢复标题时避免逐 session 分页扫描。
     */
    List<DialogueRunView> queryFirstNonBlankQueriesBySessionIds(@Param("sessionIds") List<String> sessionIds);

    /**
     * 启动对账：扫「仍处于运行态且早于宽限窗之前启动」的 run 孤儿。
     * <p>
     * 执行态全在 JVM 内存，进程被 kill -9 / OOM / 滚动发布后账本会留下永不落终态的行。
     * 启动时刻本 JVM 无任何执行体，故 {@code started_at < 启动时刻 - 宽限窗} 的运行态行必为孤儿
     * （单实例部署下是充要条件；多实例见 BackgroundRunReconciler 的 R1 风险说明）。
     * WAITING_INPUT(5) 是 HITL 合法停驻态，不在扫描范围内。
     */
    List<DialogueRun> queryOrphanedRunningRuns(@Param("startedBefore") java.time.LocalDateTime startedBefore,
                                               @Param("limit") int limit);

}
