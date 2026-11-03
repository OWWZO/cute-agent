package org.wwz.ai.domain.agent.ledger.reconcile;

import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动对账所需的账本读写端口。
 * <p>
 * 刻意与 {@link org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository} 分开：
 * 对账只在启动跑一次，只用到「三类孤儿扫描 + 四个终态写入」，单独成口可以让对账器
 * 与健康路径解耦，也便于用很小的内存 stub 做单测。
 * <p><b>扫描排序契约：</b>三个孤儿扫描方法都必须按稳定顺序返回——run / tool 按
 * {@code started_at, id} 升序，后台任务按 {@code started_at_ms, id} 升序。调用方
 * {@code BackgroundRunReconciler} 不带 cursor/offset 重复分页，依赖「已收敛的行退出结果集」
 * 推进；排序不稳定会导致同一页被反复返回（直到页数上限）或漏扫。</p>
 */
public interface IExecutionLedgerReconcileRepository {

    /**
     * 扫「仍 RUNNING 且 started_at 早于宽限窗边界」的 run 孤儿。
     */
    List<DialogueRun> queryOrphanedRunningRuns(LocalDateTime startedBefore, int limit);

    /**
     * 扫「父 run 已终态、自身仍 RUNNING 且 finished_at 为空」的工具孤儿。
     * <p>完整谓词：{@code tool_invocation.status = RUNNING}、{@code finished_at IS NULL}、
     * {@code deleted = 0}、父 run 的 status 为终态，且<b>工具自身的
     * {@code started_at < startedBefore}</b>。{@code startedBefore} 与 run 扫描共用同一个
     * 宽限窗边界，同时约束工具自己的 {@code started_at}：晚于该边界的后台工具本轮不扫，
     * 留待下一次启动。</p>
     */
    List<ToolInvocation> queryOrphanedRunningToolInvocations(LocalDateTime startedBefore, int limit);

    /**
     * 扫「仍 running 且 started_at_ms 早于宽限窗边界」的后台任务登记行。
     * <p>{@code startedBeforeMs} 与 {@code started_at_ms} 列同为 UTC epoch 毫秒
     * （写入方使用 {@code System.currentTimeMillis()}），实现必须直接按毫秒比较，
     * 不得再引入时区换算；上两个 LocalDateTime 重载对应 DATETIME 列，不要混用。</p>
     */
    List<OrphanBackgroundTaskRow> queryOrphanedRunningBackgroundTasks(long startedBeforeMs, int limit);

    /**
     * 写 run 终态。返回受影响行数，0 表示已是终态（并发/重复回调），调用方不应再累加会话计数。
     * <p><b>幂等契约：</b>实现必须保留 {@code status = RUNNING}（和/或 {@code finished_at IS NULL}）
     * 守卫，只有真正从运行态迁到终态的那一次更新返回 &gt; 0；无条件 UPDATE 会让重复/并发对账
     * 把 {@code finishedDelta}/{@code failedDelta} 重复累加到会话计数。</p>
     */
    int updateRunFinish(DialogueRun run);

    /**
     * 写工具终态。实现应保留 status=RUNNING 且 finished_at IS NULL 守卫，保证并发/重复对账幂等。
     */
    int updateToolInvocationFinish(ToolInvocation invocation);

    /** 未命中返回 {@code null}。 */
    DialogueSession querySessionBySessionId(String sessionId);

    /**
     * 会话结算并覆盖 {@code latest_summary_text}（调用方已读到可信摘要时使用）。
     */
    int updateSessionRunFinish(String sessionId,
                               int status,
                               String latestSummaryText,
                               int finishedDelta,
                               int failedDelta,
                               LocalDateTime lastActiveAt);

    /**
     * 会话结算但<b>不触碰</b> {@code latest_summary_text}。
     * <p>摘要读取失败（或会话行读不到）时使用：无条件覆盖摘要会把会话既有内容写成 null，
     * 而计数又不能因此放弃累加。所有实现必须提供只更新状态与计数的版本。</p>
     */
    int updateSessionRunFinishKeepingSummary(String sessionId,
                                             int status,
                                             int finishedDelta,
                                             int failedDelta,
                                             LocalDateTime lastActiveAt);
}
