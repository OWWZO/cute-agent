package org.wwz.ai.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.infrastructure.dao.cute.IArtifactLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueRunLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueSessionLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.ILlmInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IToolInvocationLedgerDao;

import java.util.List;

/**
 * Phase 1 执行账本写仓储适配器。
 * 继续复用现有 DAO / entity / mapper XML，只把持久化细节封在 infrastructure。
 */
@Repository
@RequiredArgsConstructor
public class ExecutionLedgerWriteRepository implements IExecutionLedgerWriteRepository {

    private final IDialogueRunLedgerDao dialogueRunLedgerDao;
    private final IDialogueSessionLedgerDao dialogueSessionLedgerDao;
    private final ILlmInvocationLedgerDao llmInvocationLedgerDao;
    private final IToolInvocationLedgerDao toolInvocationLedgerDao;
    private final IArtifactLedgerDao artifactLedgerDao;

    @Override
    public void insertRun(DialogueRun run) {
        // repository 只做领域实体到 DAO 的适配，不在基础设施层重新定义账本业务语义。
        dialogueRunLedgerDao.insertRun(run);
    }

    @Override
    public DialogueSession querySessionBySessionId(String sessionId) {
        return dialogueSessionLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public DialogueRun queryRunByRequestId(String requestId) {
        return dialogueRunLedgerDao.queryByRequestId(requestId);
    }

    @Override
    public List<LlmInvocation> queryLlmInvocationsByRunId(Long runId) {
        return llmInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ToolInvocation> queryToolInvocationsByRunId(Long runId) {
        return toolInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunId(Long runId) {
        return artifactLedgerDao.queryByRunId(runId);
    }

    @Override
    public int updateRunFinish(DialogueRun run) {
        return dialogueRunLedgerDao.updateRunFinish(run);
    }

    /**
     * 全量 upsert 会话行；调用方必须提供完整的标题、状态和计数。
     * 仅用于完整快照写入，run 启动路径应使用 {@link #upsertSessionOnRunStart(DialogueSessionUpsertRecord)}。
     */
    @Override
    public void upsertSession(DialogueSessionUpsertRecord record) {
        dialogueSessionLedgerDao.upsertSession(record);
    }

    /**
     * run 启动时的会话 upsert：已有行只原子递增 run_count，并保留既有 finished/failed 计数与 started_at。
     */
    @Override
    public void upsertSessionOnRunStart(DialogueSessionUpsertRecord record) {
        dialogueSessionLedgerDao.upsertSessionOnRunStart(record);
    }

    @Override
    public void updateSessionPreferences(String sessionId,
                                         String model,
                                         Boolean thinking,
                                         String thinkingEffort,
                                         Integer deepThink) {
        dialogueSessionLedgerDao.updateSessionPreferences(
                sessionId, model, thinking, thinkingEffort, deepThink);
    }

    @Override
    public int updateSessionRunFinish(String sessionId,
                                      int status,
                                      String latestSummaryText,
                                      int finishedDelta,
                                      int failedDelta,
                                      java.time.LocalDateTime lastActiveAt) {
        return dialogueSessionLedgerDao.updateSessionRunFinish(
                sessionId, status, latestSummaryText, finishedDelta, failedDelta, lastActiveAt);
    }

    @Override
    public List<DialogueRunView> queryRunsBySessionId(String sessionId, int limit) {
        int normalizedLimit = limit <= 0
                ? DEFAULT_RUN_SCAN_LIMIT
                : Math.min(limit, DEFAULT_RUN_SCAN_LIMIT);
        List<DialogueRunView> runs =
                dialogueRunLedgerDao.queryBySessionId(sessionId, 0, normalizedLimit);
        return runs == null ? List.of() : runs;
    }

    @Override
    public void insertLlmInvocation(LlmInvocation invocation) {
        llmInvocationLedgerDao.insertLlmInvocation(invocation);
    }

    @Override
    public void updateLlmInvocationFinish(LlmInvocation invocation) {
        llmInvocationLedgerDao.updateLlmInvocationFinish(invocation);
    }

    @Override
    public void insertToolInvocation(ToolInvocation invocation) {
        toolInvocationLedgerDao.insertToolInvocation(invocation);
    }

    @Override
    public int batchInsertToolInvocations(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return 0;
        }
        return toolInvocationLedgerDao.batchInsertToolInvocations(invocations);
    }

    @Override
    public int updateToolInvocationFinish(ToolInvocation invocation) {
        return toolInvocationLedgerDao.updateToolInvocationFinish(invocation);
    }

    @Override
    public int batchInsertArtifacts(List<ArtifactRecord> records) {
        return artifactLedgerDao.batchInsertArtifacts(records);
    }

    @Override
    public void bumpSessionEventSeq(String sessionId, long eventSeq) {
        dialogueSessionLedgerDao.bumpEventSeq(sessionId, eventSeq);
    }
}
