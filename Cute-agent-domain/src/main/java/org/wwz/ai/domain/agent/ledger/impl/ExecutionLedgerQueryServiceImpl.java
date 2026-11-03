package org.wwz.ai.domain.agent.ledger.impl;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingOwnershipPort;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionPage;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputNames;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 执行账本内部查询服务。
 */
@Service
@RequiredArgsConstructor
public class ExecutionLedgerQueryServiceImpl implements ExecutionLedgerQueryService {

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final ToolOutputReader toolOutputReader;

    /**
     * 渠道 owner 判定端口：会话归属可能是绑定的 owner 而不是会话行上的 userId。
     * 渠道模块未启用时没有实现，按 deniesAll 处理。
     */
    @Autowired
    private ObjectProvider<ChannelBindingOwnershipPort> channelBindingOwnershipPortProvider;

    @Override
    public ExecutionRunDetail queryRunDetail(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return null;
        }
        DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(requestId);
        if (run == null) {
            return null;
        }
        return buildRunDetail(run);
    }

    @Override
    public ExecutionRunDetail queryRunDetail(String userId, String requestId) {
        if (requireReadableRun(userId, requestId) == null) {
            return null;
        }
        DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(requestId);
        if (run == null) {
            return null;
        }
        return buildRunDetail(run);
    }

    private ExecutionRunDetail buildRunDetail(DialogueRun run) {
        List<LlmInvocation> llmInvocations = executionLedgerReadRepository.queryLlmInvocationsByRunId(run.getId());
        List<ToolInvocation> toolInvocations = executionLedgerReadRepository.queryToolInvocationsByRunId(run.getId());
        List<ArtifactRecord> artifacts = executionLedgerReadRepository.queryArtifactsByRunIdWithSubagentFold(run.getId());
        List<ToolInvocationView> toolViews = toToolViews(
                toolInvocations,
                run.getRequestId(),
                run.getSessionId(),
                artifacts
        );
        List<ArtifactView> artifactViews = toArtifactViews(artifacts);
        enrichStructuredOutputs(toolViews, artifactViews);
        // 先读取账本中的通用事实，再在 tool view 上延迟挂载 rich output；展示投影不能反向创建账本记录。
        // 先聚合三类通用事实，再补 rich tool output；查询层不读取旧 message/transcript 表。
        return ExecutionRunDetail.builder()
                .run(toRunView(run))
                .llmInvocations(toLlmViews(llmInvocations))
                .toolInvocations(toolViews)
                .artifacts(artifactViews)
                .build();
    }

    @Override
    public List<ArtifactView> querySessionArtifacts(String sessionId, int limit) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        int normalizedLimit = limit <= 0
                ? MAX_SESSION_ARTIFACTS
                : Math.min(limit, MAX_SESSION_ARTIFACTS);
        return toArtifactViews(executionLedgerReadRepository.queryArtifactsBySessionId(
                sessionId, normalizedLimit));
    }

    @Override
    public List<ArtifactView> querySessionArtifacts(String userId, String sessionId) {
        if (!canReadSession(userId, sessionId)) {
            return List.of();
        }
        return querySessionArtifacts(sessionId);
    }

    @Override
    public DialogueRunView queryRunSummary(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return null;
        }
        return toRunView(executionLedgerReadRepository.queryRunSummaryByRequestId(requestId));
    }

    @Override
    public DialogueRunView queryRunSummary(String userId, String requestId) {
        return toRunView(requireReadableRun(userId, requestId));
    }

    @Override
    public List<ExecutionRunDetail> queryRunDetails(List<DialogueRunView> runs) {
        if (CollectionUtils.isEmpty(runs)) {
            return List.of();
        }

        Map<Long, DialogueRunView> runViewsById = new LinkedHashMap<>();
        for (DialogueRunView run : runs) {
            if (run != null && run.getId() != null) {
                runViewsById.putIfAbsent(run.getId(), run);
            }
        }
        if (runViewsById.isEmpty()) {
            return emptyRunDetails(runs);
        }

        List<Long> runIds = new ArrayList<>(runViewsById.keySet());
        Map<Long, List<ArtifactView>> artifactsByRunId = resolveArtifactViews(runs, runIds);
        List<ArtifactView> allArtifacts = artifactsByRunId.values().stream()
                .flatMap(List::stream)
                .toList();

        // 历史回放一次加载整个 session 的事实，再在内存中按 run 分组，避免 4*N 的账本查询。
        List<LlmInvocationView> llmViews = toLlmViews(
                executionLedgerReadRepository.queryLlmInvocationsByRunIds(runIds)
        );
        List<ToolInvocationView> toolViews = toToolViews(
                executionLedgerReadRepository.queryToolInvocationsByRunIds(runIds),
                runViewsById,
                artifactsByRunId
        );
        enrichStructuredOutputs(toolViews, allArtifacts);

        Map<Long, List<LlmInvocationView>> llmViewsByRunId = llmViews.stream()
                .filter(view -> view != null && view.getRunId() != null)
                .collect(Collectors.groupingBy(
                        LlmInvocationView::getRunId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));
        Map<Long, List<ToolInvocationView>> toolViewsByRunId = toolViews.stream()
                .filter(view -> view != null && view.getRunId() != null)
                .collect(Collectors.groupingBy(
                        ToolInvocationView::getRunId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));

        List<ExecutionRunDetail> details = new ArrayList<>(runs.size());
        for (DialogueRunView run : runs) {
            if (run == null || run.getId() == null) {
                details.add(emptyRunDetail(run));
                continue;
            }
            details.add(ExecutionRunDetail.builder()
                    .run(run)
                    .llmInvocations(llmViewsByRunId.getOrDefault(run.getId(), List.of()))
                    .toolInvocations(toolViewsByRunId.getOrDefault(run.getId(), List.of()))
                    .artifacts(artifactsByRunId.getOrDefault(run.getId(), List.of()))
                    .build());
        }
        return details;
    }

    @Override
    public List<ToolInvocationView> queryRecentToolInvocations(String userId, String toolName, int limit) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(toolName)) {
            return List.of();
        }
        int pageSize = limit <= 0
                ? MAX_RECENT_TOOL_INVOCATION_PAGE_SIZE
                : Math.min(limit, MAX_RECENT_TOOL_INVOCATION_PAGE_SIZE);
        List<ToolInvocationView> views = executionLedgerReadRepository.queryRecentToolInvocations(
                userId,
                toolName,
                pageSize
        );
        enrichStructuredOutputs(views, null);
        return views;
    }

    @Override
    public List<DialogueRunView> queryRecentSessionRuns(String sessionId, int limit) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        List<DialogueRunView> runViews = executionLedgerReadRepository.queryRecentRunsBySessionId(sessionId, normalizeLimit(limit));
        return attachArtifactSummaries(runViews);
    }

    @Override
    public List<DialogueRunView> queryRecentSessionRuns(String userId, String sessionId, int limit) {
        if (!canReadSession(userId, sessionId)) {
            return List.of();
        }
        return queryRecentSessionRuns(sessionId, limit);
    }

    @Override
    public List<DialogueRunView> querySessionRuns(String sessionId, int offset, int limit) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        return attachArtifactSummaries(executionLedgerReadRepository.queryRunsBySessionId(
                sessionId, normalizeOffset(offset), normalizeSessionRunLimit(limit)));
    }

    @Override
    public List<DialogueRunView> querySessionRuns(String sessionId, RunCursor after, int limit) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        // cursor 未签名，sessionId 由调用方控制；必须绑定到当前会话，避免把别的会话
        // 的 (createTime, id) keyset 应用进来造成跳页/重复页。
        if (after != null && !after.matchesSession(sessionId)) {
            throw new IllegalArgumentException("cursor 不属于当前 session");
        }
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_SESSION_RUN_PAGE_SIZE + 1));
        return executionLedgerReadRepository.queryRunsBySessionId(sessionId, after, normalizedLimit);
    }

    @Override
    public DialogueSessionView querySession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        return restoreSessionTitle(executionLedgerReadRepository.querySession(sessionId));
    }

    @Override
    public DialogueSessionView querySessionHistorySummary(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        return executionLedgerReadRepository.querySessionHistorySummary(sessionId);
    }

    @Override
    public DialogueSessionView querySessionHistorySummary(String userId, String sessionId) {
        if (!canReadSession(userId, sessionId)) {
            return null;
        }
        return querySessionHistorySummary(sessionId);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(int limit) {
        return restoreSessionTitles(executionLedgerReadRepository.queryRecentSessions(normalizeLimit(limit)));
    }

    @Override
    public DialogueSessionView querySession(String userId, String sessionId) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            return null;
        }
        return restoreSessionTitle(executionLedgerReadRepository.querySession(userId, sessionId));
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(String userId, int limit) {
        if (StringUtils.isBlank(userId)) {
            return List.of();
        }
        return restoreSessionTitles(
                executionLedgerReadRepository.queryRecentSessions(userId, normalizeLimit(limit))
        );
    }

    @Override
    public List<DialogueSessionView> querySessionTree(String userId, String sessionId, int limit) {
        if (!canReadSession(userId, sessionId)) {
            return List.of();
        }
        return querySessionTree(sessionId, limit);
    }

    @Override
    public List<DialogueSessionView> querySessionTree(String sessionId, int limit) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        DialogueSessionView current = executionLedgerReadRepository.querySession(sessionId);
        if (current == null) {
            return List.of();
        }
        // root_session_id 是 parent_session_id 的派生缓存；历史行尚未回填时退化为「自己即根」，
        // 此时至少能返回该会话本身，不会把调用方打成空树。
        String rootSessionId = StringUtils.defaultIfBlank(current.getRootSessionId(), sessionId);
        return restoreSessionTitles(
                executionLedgerReadRepository.querySessionsByRootId(rootSessionId, normalizeLimit(limit))
        );
    }

    @Override
    public ConversationSessionPage queryRecentSessions(String userId,
                                                       ConversationSessionCursor after,
                                                       int limit) {
        if (StringUtils.isBlank(userId)) {
            return ConversationSessionPage.builder()
                    .sessions(List.of())
                    .hasMore(false)
                    .build();
        }

        int pageSize = normalizeRecentSessionPageSize(limit);
        List<DialogueSessionView> fetched = executionLedgerReadRepository.queryRecentSessions(
                userId,
                after,
                pageSize + 1
        );
        boolean hasMore = fetched.size() > pageSize;
        List<DialogueSessionView> sessions = new ArrayList<>(
                fetched.subList(0, Math.min(pageSize, fetched.size()))
        );
        String nextCursor = hasMore && !sessions.isEmpty()
                ? ConversationSessionCursor.from(sessions.get(sessions.size() - 1)).encode()
                : null;

        return ConversationSessionPage.builder()
                .sessions(restoreSessionTitles(sessions))
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 会话归属校验：会话本人，或该会话绑定的 IM 渠道 owner（与 case 层授权口径一致）。
     * <p>归属未建立（userId 为空）的会话在这里一律拒绝，不做「首个访问者自动绑定」，
     * 那是写路径授权服务的职责。</p>
     */
    private boolean canReadSession(String userId, String sessionId) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            return false;
        }
        DialogueSession session = executionLedgerReadRepository.querySessionOwnership(sessionId);
        if (session == null || StringUtils.isBlank(session.getUserId())) {
            return false;
        }
        if (StringUtils.equals(session.getUserId(), userId)) {
            return true;
        }
        if (session.getBindingId() == null) {
            return false;
        }
        ChannelBindingOwnershipPort port = channelBindingOwnershipPortProvider == null
                ? ChannelBindingOwnershipPort.deniesAll()
                : channelBindingOwnershipPortProvider.getIfAvailable(ChannelBindingOwnershipPort::deniesAll);
        return port.isOwner(session.getBindingId(), userId);
    }

    /**
     * 先由 requestId 定位 run 所属 session，再走同一套归属校验。
     *
     * @return 可读时返回轻量 run 摘要；不存在或非 owner 返回 {@code null}
     */
    private DialogueRun requireReadableRun(String userId, String requestId) {
        if (StringUtils.isAnyBlank(userId, requestId)) {
            return null;
        }
        DialogueRun run = executionLedgerReadRepository.queryRunSummaryByRequestId(requestId);
        return run != null && canReadSession(userId, run.getSessionId()) ? run : null;
    }

    /**
     * 兼容旧的 continuation run：旧逻辑可能用空 query 把 session 标题覆盖成“新对话”。
     * 查询时从同一 execution ledger 的首个非空 run query 恢复展示标题，不写回第二套事实。
     */
    private DialogueSessionView restoreSessionTitle(DialogueSessionView session) {
        if (session == null || isMeaningfulSessionTitle(session.getTitle())) {
            return session;
        }
        return applyRestoredTitle(session, resolveFirstNonBlankQuery(session.getSessionId()));
    }

    private List<DialogueSessionView> restoreSessionTitles(List<DialogueSessionView> sessions) {
        if (CollectionUtils.isEmpty(sessions)) {
            return sessions == null ? List.of() : sessions;
        }
        // 标题恢复按整页批量取首个非空 query，避免一页 N 个会话触发 N 次分页扫描。
        List<String> sessionIdsToRestore = new ArrayList<>();
        for (DialogueSessionView session : sessions) {
            if (session != null
                    && !isMeaningfulSessionTitle(session.getTitle())
                    && StringUtils.isNotBlank(session.getSessionId())) {
                sessionIdsToRestore.add(session.getSessionId());
            }
        }
        Map<String, String> firstQueries = sessionIdsToRestore.isEmpty()
                ? Map.of()
                : executionLedgerReadRepository.queryFirstNonBlankQueriesBySessionIds(sessionIdsToRestore);
        for (DialogueSessionView session : sessions) {
            if (session == null || isMeaningfulSessionTitle(session.getTitle())) {
                continue;
            }
            applyRestoredTitle(session, firstQueries.get(session.getSessionId()));
        }
        return sessions;
    }

    private DialogueSessionView applyRestoredTitle(DialogueSessionView session, String firstQuery) {
        if (StringUtils.isNotBlank(firstQuery)) {
            session.setTitle(abbreviateSessionTitle(firstQuery));
            if (StringUtils.isBlank(session.getLatestQueryText())) {
                session.setLatestQueryText(firstQuery);
            }
        }
        return session;
    }

    private String resolveFirstNonBlankQuery(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        int offset = 0;
        final int pageSize = ExecutionLedgerQueryService.DEFAULT_SESSION_RUN_PAGE_SIZE;
        while (true) {
            List<DialogueRunView> page = executionLedgerReadRepository.queryRunsBySessionId(
                    sessionId, offset, pageSize);
            if (CollectionUtils.isEmpty(page)) {
                return null;
            }
            for (DialogueRunView run : page) {
                if (run != null && StringUtils.isNotBlank(run.getQueryText())) {
                    return run.getQueryText();
                }
            }
            if (page.size() < pageSize) {
                return null;
            }
            offset += pageSize;
        }
    }

    private boolean isMeaningfulSessionTitle(String title) {
        return StringUtils.isNotBlank(title) && !ExecutionLedgerConstants.DEFAULT_SESSION_TITLE.equals(title.trim());
    }

    private String abbreviateSessionTitle(String queryText) {
        String normalized = StringUtils.trimToEmpty(queryText);
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private List<DialogueRunView> attachArtifactSummaries(List<DialogueRunView> runViews) {
        if (CollectionUtils.isEmpty(runViews)) {
            return runViews == null ? List.of() : runViews;
        }
        List<Long> runIds = runViews.stream()
                .map(DialogueRunView::getId)
                .filter(id -> id != null)
                .toList();
        if (runIds.isEmpty()) {
            return runViews;
        }
        // 批量按 run 查询 artifact，避免会话列表产生 N+1；没有 id 的视图保持原样。
        // 会话摘要列表只需要轻量文件概览，这里统一补到 run 视图上，
        // 避免 controller / UI 再额外扫 artifact 表做第二次拼装。
        Map<Long, List<ArtifactView>> artifactViewsByRunId = executionLedgerReadRepository
                .queryArtifactsByRunIdsWithSubagentFold(runIds).stream()
                // ArtifactRecord.runId 可空（直接请求产物挂在 requestId 上）；groupingBy 不接受 null key。
                .filter(artifact -> artifact != null && artifact.getRunId() != null)
                .collect(Collectors.groupingBy(
                        ArtifactRecord::getRunId,
                        LinkedHashMap::new,
                        Collectors.mapping(this::toArtifactView, Collectors.toCollection(ArrayList::new))));
        for (DialogueRunView runView : runViews) {
            runView.setArtifactSummaries(artifactViewsByRunId.getOrDefault(runView.getId(), List.of()));
        }
        return runViews;
    }

    private int normalizeLimit(int limit) {
        // 最近会话列表和最近 run 摘要统一走同一套 limit 归一规则，
        // 默认 20、上限 100，避免不同入口出现分页语义分叉。
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private int normalizeSessionRunLimit(int limit) {
        if (limit <= 0) {
            return ExecutionLedgerQueryService.DEFAULT_SESSION_RUN_PAGE_SIZE;
        }
        return Math.min(limit, ExecutionLedgerQueryService.MAX_SESSION_RUN_PAGE_SIZE);
    }

    private int normalizeRecentSessionPageSize(int limit) {
        if (limit <= 0) {
            return ExecutionLedgerQueryService.DEFAULT_RECENT_SESSION_PAGE_SIZE;
        }
        return Math.min(limit, ExecutionLedgerQueryService.MAX_RECENT_SESSION_PAGE_SIZE);
    }

    private int normalizeOffset(int offset) {
        return Math.max(offset, 0);
    }

    private DialogueRunView toRunView(DialogueRun run) {
        if (run == null) {
            return null;
        }
        return DialogueRunView.builder()
                .id(run.getId())
                .runUid(run.getRunUid())
                .requestId(run.getRequestId())
                .sessionId(run.getSessionId())
                .userId(run.getUserId())
                .entryAgent(run.getEntryAgent())
                .status(run.getStatus())
                .queryText(run.getQueryText())
                .finalSummaryText(run.getFinalSummaryText())
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .promptTokensTotal(run.getPromptTokensTotal())
                .completionTokensTotal(run.getCompletionTokensTotal())
                .totalTokensTotal(run.getTotalTokensTotal())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .createTime(run.getCreateTime())
                .build();
    }

    private List<LlmInvocationView> toLlmViews(List<LlmInvocation> invocations) {
        if (invocations == null) {
            return List.of();
        }
        List<LlmInvocationView> views = new ArrayList<>(invocations.size());
        for (LlmInvocation invocation : invocations) {
            views.add(LlmInvocationView.builder()
                    .id(invocation.getId())
                    .runId(invocation.getRunId())
                    .invocationSeq(invocation.getInvocationSeq())
                    .agentName(invocation.getAgentName())
                    .stepNo(invocation.getStepNo())
                    .callKind(invocation.getCallKind())
                    .streaming(invocation.getStreaming())
                    .modelName(invocation.getModelName())
                    .responseText(invocation.getResponseText())
                    .reasoningContent(invocation.getReasoningContent())
                    .toolCallCount(invocation.getToolCallCount())
                    .promptTokens(invocation.getPromptTokens())
                    .completionTokens(invocation.getCompletionTokens())
                    .totalTokens(invocation.getTotalTokens())
                    .estTotalTokens(invocation.getEstTotalTokens())
                    .estSystemTokens(invocation.getEstSystemTokens())
                    .estMessageTokens(invocation.getEstMessageTokens())
                    .estToolTokens(invocation.getEstToolTokens())
                    .finishReason(invocation.getFinishReason())
                    .status(invocation.getStatus())
                    .errorMsg(invocation.getErrorMsg())
                    .startedAt(invocation.getStartedAt())
                    .finishedAt(invocation.getFinishedAt())
                    .durationMs(invocation.getDurationMs())
                    .createTime(invocation.getCreateTime())
                    .build());
        }
        return views;
    }

    private List<ToolInvocationView> toToolViews(List<ToolInvocation> invocations,
                                                  String requestId,
                                                  String sessionId,
                                                  List<ArtifactRecord> artifacts) {
        if (invocations == null) {
            return List.of();
        }
        Map<Long, Integer> artifactCountByToolInvocationId = new LinkedHashMap<>();
        if (artifacts != null) {
            // artifact 数量从同一批查询结果聚合，避免为每个 tool invocation 再访问数据库。
            for (ArtifactRecord artifact : artifacts) {
                if (artifact == null || artifact.getToolInvocationId() == null) {
                    continue;
                }
                artifactCountByToolInvocationId.merge(artifact.getToolInvocationId(), 1, Integer::sum);
            }
        }
        List<ToolInvocationView> views = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            views.add(toToolView(
                    invocation,
                    requestId,
                    sessionId,
                    artifactCountByToolInvocationId.getOrDefault(invocation.getId(), 0)
            ));
        }
        return views;
    }

    private List<ToolInvocationView> toToolViews(List<ToolInvocation> invocations,
                                                  Map<Long, DialogueRunView> runViewsById,
                                                  Map<Long, List<ArtifactView>> artifactsByRunId) {
        if (CollectionUtils.isEmpty(invocations)) {
            return List.of();
        }
        Map<Long, Integer> artifactCountByToolInvocationId = new LinkedHashMap<>();
        if (artifactsByRunId != null) {
            for (List<ArtifactView> artifacts : artifactsByRunId.values()) {
                if (artifacts == null) {
                    continue;
                }
                for (ArtifactView artifact : artifacts) {
                    if (artifact == null || artifact.getToolInvocationId() == null) {
                        continue;
                    }
                    artifactCountByToolInvocationId.merge(artifact.getToolInvocationId(), 1, Integer::sum);
                }
            }
        }
        List<ToolInvocationView> views = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            DialogueRunView run = runViewsById.get(invocation.getRunId());
            views.add(toToolView(
                    invocation,
                    run == null ? null : run.getRequestId(),
                    run == null ? null : run.getSessionId(),
                    artifactCountByToolInvocationId.getOrDefault(invocation.getId(), 0)
            ));
        }
        return views;
    }

    private ToolInvocationView toToolView(ToolInvocation invocation,
                                           String requestId,
                                           String sessionId,
                                           int artifactCount) {
        return ToolInvocationView.builder()
                .id(invocation.getId())
                .runId(invocation.getRunId())
                .llmInvocationId(invocation.getLlmInvocationId())
                .requestId(requestId)
                .sessionId(sessionId)
                .toolCallId(invocation.getToolCallId())
                .parentToolCallId(invocation.getParentToolCallId())
                .subAgentId(invocation.getSubAgentId())
                .subAgentType(invocation.getSubAgentType())
                .subAgentDescription(invocation.getSubAgentDescription())
                .dispatchIndex(invocation.getDispatchIndex())
                .agentName(invocation.getAgentName())
                .stepNo(invocation.getStepNo())
                .toolName(invocation.getToolName())
                .toolProvider(invocation.getToolProvider())
                .inputJson(invocation.getInputJson())
                .llmObservation(invocation.getLlmObservation())
                .toolResult(invocation.getToolResult())
                .status(invocation.getStatus())
                .errorMsg(invocation.getErrorMsg())
                .durationMs(invocation.getDurationMs())
                .artifactCount(artifactCount)
                .startedAt(invocation.getStartedAt())
                .finishedAt(invocation.getFinishedAt())
                .createTime(invocation.getCreateTime())
                .build();
    }

    private void enrichStructuredOutputs(List<ToolInvocationView> views, List<ArtifactView> artifacts) {
        if (CollectionUtils.isEmpty(views) || toolOutputReader == null) {
            return;
        }
        List<ToolInvocationView> richViews = views.stream()
                .filter(view -> view != null
                        && view.getId() != null
                        && ToolOutputNames.isPersistedTool(view.getToolName()))
                .toList();
        if (richViews.isEmpty()) {
            return;
        }
        Map<Long, ToolStructuredOutput> outputs = toolOutputReader.readByInvocationIds(richViews, artifacts);
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        for (ToolInvocationView view : richViews) {
            ToolStructuredOutput output = outputs.get(view.getId());
            if (output != null) {
                view.setStructuredOutput(output);
            }
        }
    }

    private Map<Long, List<ArtifactView>> resolveArtifactViews(List<DialogueRunView> runs, List<Long> runIds) {
        boolean summariesLoaded = true;
        for (DialogueRunView run : runs) {
            if (run != null && run.getId() != null && run.getArtifactSummaries() == null) {
                summariesLoaded = false;
                break;
            }
        }

        List<ArtifactView> artifacts;
        if (summariesLoaded) {
            artifacts = runs.stream()
                    .filter(run -> run != null && run.getArtifactSummaries() != null)
                    .flatMap(run -> run.getArtifactSummaries().stream())
                    .filter(artifact -> artifact != null)
                    .toList();
        } else {
            artifacts = toArtifactViews(executionLedgerReadRepository.queryArtifactsByRunIdsWithSubagentFold(runIds));
        }
        return artifacts.stream()
                .filter(artifact -> artifact.getRunId() != null)
                .collect(Collectors.groupingBy(
                        ArtifactView::getRunId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));
    }

    private List<ExecutionRunDetail> emptyRunDetails(List<DialogueRunView> runs) {
        List<ExecutionRunDetail> details = new ArrayList<>(runs.size());
        for (DialogueRunView run : runs) {
            details.add(emptyRunDetail(run));
        }
        return details;
    }

    private ExecutionRunDetail emptyRunDetail(DialogueRunView run) {
        return ExecutionRunDetail.builder()
                .run(run)
                .llmInvocations(List.of())
                .toolInvocations(List.of())
                .artifacts(run == null || run.getArtifactSummaries() == null
                        ? List.of()
                        : run.getArtifactSummaries())
                .build();
    }

    private List<ArtifactView> toArtifactViews(List<ArtifactRecord> artifacts) {
        if (artifacts == null) {
            return List.of();
        }
        List<ArtifactView> views = new ArrayList<>(artifacts.size());
        for (ArtifactRecord artifact : artifacts) {
            views.add(toArtifactView(artifact));
        }
        return views;
    }

    private ArtifactView toArtifactView(ArtifactRecord artifact) {
        return ArtifactView.builder()
                .id(artifact.getId())
                .runId(artifact.getRunId())
                .requestId(artifact.getRequestId())
                .toolInvocationId(artifact.getToolInvocationId())
                .toolCallId(artifact.getToolCallId())
                .artifactRole(artifact.getArtifactRole())
                .visibility(artifact.getVisibility())
                .sourceType(artifact.getSourceType())
                .sourceName(artifact.getSourceName())
                .fileName(artifact.getFileName())
                .storageKey(artifact.getStorageKey())
                .downloadUrl(artifact.getDownloadUrl())
                .previewUrl(artifact.getPreviewUrl())
                .mimeType(artifact.getMimeType())
                .fileSize(artifact.getFileSize())
                .fileHash(artifact.getFileHash())
                .metadataJson(artifact.getMetadataJson())
                .createTime(artifact.getCreateTime())
                .build();
    }
}
