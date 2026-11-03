package org.wwz.ai.domain.agent.ledger.reconcile;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动对账器：把进程消亡留下的「运行态孤儿」一次性收敛到终态。
 * <p>
 * 三类孤儿（执行态全在 JVM 内存，kill -9 / OOM / 滚动发布都会留下）：
 * <ul>
 *   <li>Z1 根 run：{@code dialogue_run.status=RUNNING} 永不落终态 → 会话列表永远「运行中」；</li>
 *   <li>Z2 后台工具：父 run 已终态但 {@code tool_invocation} 仍 running → 父卡永远转圈；</li>
 *   <li>Z3 后台任务登记行：{@code background_task.status='running'} → TaskOutput 永远说在跑。</li>
 * </ul>
 * 判定依据：启动时刻本 JVM 无任何执行体，故「状态仍为运行态 且 started_at &lt; 启动时刻 - 宽限窗」
 * 的行必为孤儿（单实例部署下是充要条件）。多实例滚动发布会误杀别的实例在跑的任务——
 * 由 {@code autobots.autoagent.background.reconcile-on-start} 开关兜底，多实例部署须置 false。
 * <p>
 * 幂等：R1 靠 {@code updateRunFinish} 的终态守卫（返回 0 行即已收敛，不再累加会话计数）；
 * R2/R3 靠扫描条件本身（收敛后不再满足 running 条件）。重复执行无副作用。
 */
@Slf4j
public class BackgroundRunReconciler {

    public static final String ERROR_CODE_SERVER_RESTART = "SERVER_RESTART";
    public static final String REASON_SERVER_RESTART = "server restarted";

    /** 单表扫描的分页上限，防止极端数据量下一页拉爆内存；也用于判断「是否还有下一页」。 */
    private static final int MAX_PAGES = 200;

    /** 会话结算的重试次数：run 已终态后不会被重新扫描，瞬时失败必须在这里重试。 */
    private static final int SESSION_SETTLE_ATTEMPTS = 3;

    private final IExecutionLedgerReconcileRepository repository;
    private final TasklistPersistencePort tasklist;
    private final long graceMs;
    private final int batchSize;

    public BackgroundRunReconciler(IExecutionLedgerReconcileRepository repository,
                                   TasklistPersistencePort tasklist,
                                   long graceMs,
                                   int batchSize) {
        this.repository = repository;
        this.tasklist = tasklist;
        this.graceMs = Math.max(0L, graceMs);
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
    }

    public Report reconcile(LocalDateTime now) {
        LocalDateTime settledNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime runCutoff = settledNow.minusNanos(graceMs * 1_000_000L);
        long taskCutoffMs = toMillis(settledNow) - graceMs;
        Report report = new Report();
        report.setStartedAt(settledNow);
        report.setRunCutoff(runCutoff);
        report.setRunConverged(reconcileOrphanRuns(settledNow, runCutoff, report));
        report.setToolConverged(reconcileOrphanToolInvocations(settledNow, runCutoff, report));
        report.setTaskConverged(reconcileOrphanBackgroundTasks(taskCutoffMs, report));
        report.setFinishedAt(LocalDateTime.now());
        log.info("reconcile.summary runs={} tools={} tasks={} errors={} cutoff={}",
                report.getRunConverged(), report.getToolConverged(), report.getTaskConverged(),
                report.getErrors().size(), runCutoff);
        return report;
    }

    /**
     * Z1：孤儿 run → STOPPED，并修正会话聚合计数与状态。
     * 必须先于 Z2 执行：Z2 的扫描条件是「父 run 已终态」，包含这里刚收敛的 run。
     */
    private int reconcileOrphanRuns(LocalDateTime now, LocalDateTime cutoff, Report report) {
        int converged = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<DialogueRun> batch;
            try {
                batch = repository.queryOrphanedRunningRuns(cutoff, batchSize);
            } catch (Exception e) {
                report.addError("run_scan", e);
                return converged;
            }
            if (batch == null || batch.isEmpty()) {
                return converged;
            }
            int pageConverged = 0;
            for (DialogueRun run : batch) {
                if (run == null || run.getId() == null) {
                    continue;
                }
                try {
                    int settled = settleRun(run, now, report);
                    converged += settled;
                    pageConverged += settled;
                } catch (Exception e) {
                    report.addError("run_settle#" + run.getId(), e);
                }
            }
            if (batch.size() < batchSize) {
                return converged;
            }
            if (pageConverged == 0) {
                // 页内没有任何行收敛时，下一页仍会返回同一批未收敛行；继续翻页只会重复报错。
                log.warn("reconcile.run_orphan 本页无进展（{} 行），停止扫描避免重复处理", batch.size());
                return converged;
            }
        }
        log.warn("reconcile.run_orphan 扫描页数达到上限 {} 仍未结束，剩余孤儿留待下次启动", MAX_PAGES);
        return converged;
    }

    private int settleRun(DialogueRun run, LocalDateTime now, Report report) {
        run.setStatus(ExecutionLedgerConstants.STATUS_STOPPED);
        run.setErrorCode(ERROR_CODE_SERVER_RESTART);
        run.setErrorMsg(REASON_SERVER_RESTART);
        run.setFinishedAt(now);
        run.setDurationMs(run.getStartedAt() == null
                ? null
                : Duration.between(run.getStartedAt(), now).toMillis());
        int updated = repository.updateRunFinish(run);
        if (updated == 0) {
            // 已是终态：不覆盖状态、不重复累加会话计数。
            return 0;
        }
        if (StringUtils.isBlank(run.getSessionId())) {
            return 1;
        }
        // STOPPED 计入 finished 与 failed（与 AgentExecutionRecorderImpl.isFailedStatus 一致）。
        // 摘要该 UPDATE 是无条件覆盖：只有确实读到会话现值时才走覆盖版本，
        // 读取失败必须走不碰摘要列的结算，否则一次瞬时读故障就会清空既有摘要。
        String summary = null;
        boolean summaryRead = false;
        try {
            DialogueSession session = repository.querySessionBySessionId(run.getSessionId());
            if (session != null) {
                summary = session.getLatestSummaryText();
                summaryRead = true;
            }
        } catch (Exception e) {
            report.addError("session_summary#" + run.getId(), e);
        }
        settleSession(run, now, summary, summaryRead, report);
        return 1;
    }

    /**
     * 会话结算独立收口：run 已经是终态，不再会被下一轮扫描命中，
     * 因此这里失败必须记录并重试，不能把计数永久丢掉。
     */
    private void settleSession(DialogueRun run, LocalDateTime now, String summary, boolean summaryRead, Report report) {
        for (int attempt = 1; attempt <= SESSION_SETTLE_ATTEMPTS; attempt++) {
            try {
                if (summaryRead) {
                    repository.updateSessionRunFinish(
                            run.getSessionId(),
                            ExecutionLedgerConstants.STATUS_STOPPED,
                            summary,
                            1,
                            1,
                            now);
                } else {
                    repository.updateSessionRunFinishKeepingSummary(
                            run.getSessionId(),
                            ExecutionLedgerConstants.STATUS_STOPPED,
                            1,
                            1,
                            now);
                }
                return;
            } catch (Exception e) {
                if (attempt >= SESSION_SETTLE_ATTEMPTS) {
                    report.addError("session_settle#" + run.getId(), e);
                }
            }
        }
    }

    /** Z2：父 run 已终态但仍 running 的工具 → STOPPED；回放投影据此生成终态 {@code tool_result}。 */
    private int reconcileOrphanToolInvocations(LocalDateTime now, LocalDateTime cutoff, Report report) {
        int converged = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ToolInvocation> batch;
            try {
                batch = repository.queryOrphanedRunningToolInvocations(cutoff, batchSize);
            } catch (Exception e) {
                report.addError("tool_scan", e);
                return converged;
            }
            if (batch == null || batch.isEmpty()) {
                return converged;
            }
            int pageConverged = 0;
            for (ToolInvocation invocation : batch) {
                if (invocation == null || invocation.getId() == null) {
                    continue;
                }
                try {
                    invocation.setStatus(ExecutionLedgerConstants.STATUS_STOPPED);
                    invocation.setErrorMsg(REASON_SERVER_RESTART);
                    invocation.setFinishedAt(now);
                    if (repository.updateToolInvocationFinish(invocation) > 0) {
                        converged++;
                        pageConverged++;
                    }
                } catch (Exception e) {
                    report.addError("tool_settle#" + invocation.getId(), e);
                }
            }
            if (batch.size() < batchSize) {
                return converged;
            }
            if (pageConverged == 0) {
                log.warn("reconcile.tool_orphan 本页无进展（{} 行），停止扫描避免重复处理", batch.size());
                return converged;
            }
        }
        log.warn("reconcile.tool_orphan 扫描页数达到上限 {} 仍未结束，剩余孤儿留待下次启动", MAX_PAGES);
        return converged;
    }

    /** Z3：无主的 running 后台任务登记行 → {@code lost}。 */
    private int reconcileOrphanBackgroundTasks(long cutoffMs, Report report) {
        if (tasklist == null) {
            return 0;
        }
        int converged = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<OrphanBackgroundTaskRow> batch;
            try {
                batch = repository.queryOrphanedRunningBackgroundTasks(cutoffMs, batchSize);
            } catch (Exception e) {
                report.addError("task_scan", e);
                return converged;
            }
            if (batch == null || batch.isEmpty()) {
                return converged;
            }
            int pageConverged = 0;
            for (OrphanBackgroundTaskRow row : batch) {
                if (row == null || row.getTask() == null || StringUtils.isBlank(row.getSessionId())) {
                    continue;
                }
                RuntimeBackgroundTask task = row.getTask();
                try {
                    task.setStatus(RuntimeBackgroundTask.STATUS_LOST);
                    task.setEndedAtMs(toMillis(LocalDateTime.now()));
                    task.setErrorMsg(StringUtils.defaultIfBlank(task.getErrorMsg(), REASON_SERVER_RESTART));
                    tasklist.upsertBackgroundTask(row.getSessionId(), task);
                    converged++;
                    pageConverged++;
                } catch (Exception e) {
                    report.addError("task_settle#" + task.getId(), e);
                }
            }
            if (batch.size() < batchSize) {
                return converged;
            }
            if (pageConverged == 0) {
                // 空 sessionId / upsert 失败的行不会被扫描条件过滤，继续翻页只会重复命中。
                log.warn("reconcile.task_row_lost 本页无进展（{} 行），停止扫描避免重复处理", batch.size());
                return converged;
            }
        }
        log.warn("reconcile.task_row_lost 扫描页数达到上限 {} 仍未结束，剩余孤儿留待下次启动", MAX_PAGES);
        return converged;
    }

    private static long toMillis(LocalDateTime time) {
        if (time == null) {
            return System.currentTimeMillis();
        }
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Data
    public static class Report {

        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private LocalDateTime runCutoff;
        private int runConverged;
        private int toolConverged;
        private int taskConverged;
        private final List<String> errors = new ArrayList<>();

        public void addError(String scope, Exception e) {
            errors.add(scope + ": " + (e == null ? "unknown" : e.getMessage()));
            log.error("reconcile.{} failed", scope, e);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
}
