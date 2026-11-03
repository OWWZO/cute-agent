package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 1 执行账本写仓储端口。
 * 仅暴露当前 Recorder 所需的领域级写操作，屏蔽底层 DAO 细节。
 */
public interface IExecutionLedgerWriteRepository {

    void insertRun(DialogueRun run);

    /** 未命中返回 {@code null}。 */
    DialogueSession querySessionBySessionId(String sessionId);

    /** 未命中返回 {@code null}。 */
    DialogueRun queryRunByRequestId(String requestId);

    List<LlmInvocation> queryLlmInvocationsByRunId(Long runId);

    List<ToolInvocation> queryToolInvocationsByRunId(Long runId);

    List<ArtifactRecord> queryArtifactsByRunId(Long runId);

    /**
     * 写入 run 终态。返回受影响行数：0 表示该 run 已是终态（陈旧/重复回调），调用方不应再累加会话计数。
     */
    int updateRunFinish(DialogueRun run);

    void upsertSession(DialogueSessionUpsertRecord record);

    /**
     * 只更新会话偏好四列（model / thinking / thinking_effort / deep_think）。
     * <p>与 {@link #upsertSession} 的区别：那个会整行覆盖 title/status/run_count 等列，
     * 传入 null 就把库里的值清空；本方法只碰偏好列，null 表示「不清空」。</p>
     */
    void updateSessionPreferences(String sessionId,
                                  String model,
                                  Boolean thinking,
                                  String thinkingEffort,
                                  Integer deepThink);

    /**
     * run 启动：会话 run_count 原子 +1（行不存在则新建），不做读-改-写。
     */
    void upsertSessionOnRunStart(DialogueSessionUpsertRecord record);

    /**
     * run 结束：按终态原子累加 finished/failed 计数。
     *
     * @return 受影响行数：0 表示会话行不存在或已删除（计数未更新），1 表示已更新
     */
    int updateSessionRunFinish(String sessionId,
                               int status,
                               String latestSummaryText,
                               int finishedDelta,
                               int failedDelta,
                               LocalDateTime lastActiveAt);

    List<org.wwz.ai.domain.agent.ledger.model.DialogueRunView> queryRunsBySessionId(String sessionId);

    void insertLlmInvocation(LlmInvocation invocation);

    void updateLlmInvocationFinish(LlmInvocation invocation);

    void insertToolInvocation(ToolInvocation invocation);

    /**
     * 结算工具调用终态。守卫与 {@code IExecutionLedgerReconcileRepository#updateToolInvocationFinish}
     * 一致（仅更新 {@code status=RUNNING AND finished_at IS NULL} 的行），返回受影响行数。
     *
     * @return 0 表示该调用已结算过（重复/迟到回调）或不存在，调用方据此保证幂等；
     *         返回类型与 reconcile 端口一致，使单一适配器可以同时实现两个端口
     */
    int updateToolInvocationFinish(ToolInvocation invocation);

    int batchInsertArtifacts(List<ArtifactRecord> records);

    void bumpSessionEventSeq(String sessionId, long eventSeq);
}
