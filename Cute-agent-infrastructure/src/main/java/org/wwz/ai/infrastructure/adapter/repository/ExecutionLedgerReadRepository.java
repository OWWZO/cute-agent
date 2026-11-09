package org.wwz.ai.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.WorkingMemoryConversationRow;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkExecutionEvent;
import org.wwz.ai.infrastructure.dao.cute.IArtifactLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueRunLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueSessionLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.ILlmInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IToolInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryCompactionReadDao;
import org.wwz.ai.infrastructure.dao.cute.ILtmForkExecutionReadDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryConversationReadDao;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 执行账本读仓储适配器。
 *
 * <p>这里只把 DAO 查询结果映射为 ledger 读模型，不创建历史消息表或其它第二套持久化真相。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ExecutionLedgerReadRepository implements IExecutionLedgerReadRepository {

    private final IDialogueRunLedgerDao dialogueRunLedgerDao;
    private final IDialogueSessionLedgerDao dialogueSessionLedgerDao;
    private final ILlmInvocationLedgerDao llmInvocationLedgerDao;
    private final IToolInvocationLedgerDao toolInvocationLedgerDao;
    private final IArtifactLedgerDao artifactLedgerDao;
    private final IWorkingMemoryCompactionReadDao workingMemoryCompactionReadDao;
    private final ILtmForkExecutionReadDao ltmForkExecutionReadDao;
    private final IWorkingMemoryConversationReadDao workingMemoryConversationReadDao;

    @Override
    public DialogueRun queryRunByRequestId(String requestId) {
        // requestId 是运行时外部关联键，先定位 run，再由调用方按 runId 查询 LLM、tool 和 artifact 事实。
        return dialogueRunLedgerDao.queryByRequestId(requestId);
    }

    @Override
    public DialogueRun queryRunSummaryByRequestId(String requestId) {
        return dialogueRunLedgerDao.querySummaryByRequestId(requestId);
    }

    @Override
    public List<LlmInvocation> queryLlmInvocationsByRunId(Long runId) {
        return llmInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<LlmInvocation> queryLlmInvocationsByRunIds(List<Long> runIds) {
        return llmInvocationLedgerDao.queryByRunIds(runIds);
    }

    @Override
    public List<ToolInvocation> queryToolInvocationsByRunId(Long runId) {
        return toolInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ToolInvocation> queryToolInvocationsByRunIds(List<Long> runIds) {
        return toolInvocationLedgerDao.queryByRunIds(runIds);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunId(Long runId) {
        return artifactLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunIdWithSubagentFold(Long runId) {
        return artifactLedgerDao.queryByRunIdWithSubagentFold(runId);
    }

    @Override
    public List<ToolInvocationView> queryRecentToolInvocations(String userId, String toolName, int limit) {
        // recent 查询直接返回投影所需视图，仓储不在这里拼装前端事件或改变账本事实。
        // 用户条件下推到 SQL，避免先查全量再在内存过滤。
        return toolInvocationLedgerDao.queryRecentByToolName(userId, toolName, limit);
    }

    @Override
    public List<DialogueRunView> queryRecentRunsBySessionId(String sessionId, int limit) {
        return dialogueRunLedgerDao.queryRecentBySessionId(sessionId, limit);
    }

    @Override
    public List<DialogueRunView> queryRunsBySessionId(String sessionId, int offset, int limit) {
        int normalizedOffset = Math.max(offset, 0);
        int normalizedLimit = limit <= 0 ? 100 : Math.min(limit, 100);
        return dialogueRunLedgerDao.queryBySessionId(sessionId, normalizedOffset, normalizedLimit);
    }

    @Override
    public List<DialogueRunView> queryRunsByTimeRangeAfter(
            LocalDateTime from,
            LocalDateTime to,
            List<Integer> statuses,
            LocalDateTime afterCreateTime,
            Long afterId,
            int limit) {
        int normalizedLimit = limit <= 0 ? 100 : Math.min(limit, 100);
        return dialogueRunLedgerDao.queryByTimeRangeAfter(
                from,
                to,
                statuses,
                afterCreateTime,
                afterId,
                normalizedLimit
        );
    }

    @Override
    public List<DialogueRunView> queryRunsBySessionId(String sessionId, RunCursor after, int limit) {
        int normalizedLimit = limit <= 0 ? 1 : Math.min(limit, 101);
        return dialogueRunLedgerDao.queryBySessionIdAfter(
                sessionId,
                after == null ? null : after.getCreateTime(),
                after == null ? null : after.getId(),
                normalizedLimit
        );
    }

    @Override
    public DialogueSession querySessionEntity(String sessionId) {
        return dialogueSessionLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public DialogueSession querySessionOwnership(String sessionId) {
        return dialogueSessionLedgerDao.querySessionOwnership(sessionId);
    }

    @Override
    public DialogueSessionView querySession(String sessionId) {
        return dialogueSessionLedgerDao.querySessionView(sessionId);
    }

    @Override
    public DialogueSessionView querySessionHistorySummary(String sessionId) {
        return dialogueSessionLedgerDao.querySessionHistoryView(sessionId);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(int limit) {
        return dialogueSessionLedgerDao.queryRecentSessions(limit);
    }

    @Override
    public DialogueSessionView querySession(String userId, String sessionId) {
        return dialogueSessionLedgerDao.querySessionViewByUserId(userId, sessionId);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(String userId, int limit) {
        // 用户过滤在 DAO 层执行，避免先查全量 session 再在内存中泄露或混入其他用户数据。
        return dialogueSessionLedgerDao.queryRecentSessionsByUserId(userId, limit);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(String userId,
                                                         ConversationSessionCursor after,
                                                         int limit) {
        return dialogueSessionLedgerDao.queryRecentSessionsByUserIdAfter(
                userId,
                after == null ? null : after.getLastActiveAt(),
                after == null ? null : after.getId(),
                limit
        );
    }

    @Override
    public List<DialogueSessionView> querySessionsByBindingId(Long bindingId, int limit) {
        return dialogueSessionLedgerDao.querySessionsByBindingId(bindingId, limit);
    }

    @Override
    public List<DialogueSessionView> querySessionsByRootId(String rootSessionId, int limit) {
        return dialogueSessionLedgerDao.querySessionsByRootId(rootSessionId, limit);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunIds(List<Long> runIds) {
        return artifactLedgerDao.queryByRunIds(runIds);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunIdsWithSubagentFold(List<Long> runIds) {
        return artifactLedgerDao.queryByRunIdsWithSubagentFold(runIds);
    }

    @Override
    public List<WorkingMemoryCompactionEvent> queryCompactionEventsByTriggerRequestIds(
            List<String> triggerRequestIds) {
        if (triggerRequestIds == null || triggerRequestIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = triggerRequestIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAX_BATCH_RUN_IDS)
                .toList();
        return ids.isEmpty()
                ? List.of()
                : workingMemoryCompactionReadDao.queryByTriggerRequestIds(ids);
    }

    @Override
    public List<LtmForkExecutionEvent> queryMemoryForkEventsByTriggerRequestIds(
            List<String> triggerRequestIds) {
        if (triggerRequestIds == null || triggerRequestIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = triggerRequestIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAX_BATCH_RUN_IDS)
                .toList();
        return ids.isEmpty()
                ? List.of()
                : ltmForkExecutionReadDao.queryByTriggerRequestIds(ids);
    }

    @Override
    public List<WorkingMemoryConversationRow> queryConversationBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return queryConversationBySessionIds(List.of(sessionId));
    }

    @Override
    public List<WorkingMemoryConversationRow> queryConversationBySessionIds(
            List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAX_BATCH_RUN_IDS)
                .toList();
        return ids.isEmpty()
                ? List.of()
                : workingMemoryConversationReadDao.queryBySessionIds(ids);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsBySessionId(String sessionId, int limit) {
        int normalizedLimit = limit <= 0
                ? DEFAULT_SESSION_ARTIFACT_LIMIT
                : Math.min(limit, DEFAULT_SESSION_ARTIFACT_LIMIT);
        List<ArtifactRecord> artifacts =
                artifactLedgerDao.queryBySessionIdLimit(sessionId, normalizedLimit);
        if (artifacts != null && artifacts.size() >= normalizedLimit) {
            // 没有总数查询，命中上限即提示可能截断；调用方需要更完整数据时应按 run 分页聚合。
            log.warn("artifact.session_query reached limit sessionId={} limit={}",
                    sessionId, normalizedLimit);
        }
        return artifacts;
    }

    @Override
    public Map<String, String> queryFirstNonBlankQueriesBySessionIds(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        // 一次 IN 查询即可覆盖一个分页的 session；去重并收敛到批量上限，避免 SQL 参数无界。
        List<String> ids = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAX_BATCH_RUN_IDS)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> firstQueries = new LinkedHashMap<>();
        for (DialogueRunView run : dialogueRunLedgerDao.queryFirstNonBlankQueriesBySessionIds(ids)) {
            if (run != null && run.getSessionId() != null && run.getQueryText() != null) {
                firstQueries.putIfAbsent(run.getSessionId(), run.getQueryText());
            }
        }
        return firstQueries;
    }

    @Override
    public List<ArtifactRecord> queryArtifactsBySessionId(String sessionId) {
        return queryArtifactsBySessionId(sessionId, DEFAULT_SESSION_ARTIFACT_LIMIT);
    }
}
