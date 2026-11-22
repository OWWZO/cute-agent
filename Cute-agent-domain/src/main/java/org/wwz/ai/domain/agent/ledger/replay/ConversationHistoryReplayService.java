package org.wwz.ai.domain.agent.ledger.replay;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryDetail;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunSummary;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayFactBundle;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话历史摘要与回放聚合服务。
 * <p>
 * 摘要只读取 Execution Ledger 的 session/run 主表；单 run replay 再交给
 * ReplayProjector 生成前端事件。working memory 仅服务下一轮 prompt，不参与用户可见历史回放。
 */
@Slf4j
public class ConversationHistoryReplayService {

    public static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    public static final int MAX_HISTORY_PAGE_SIZE = 100;
    /** 兼容视图单次最多回放的 run 数；超长会话应改用分页接口。 */
    public static final int MAX_HISTORY_RUN_COUNT = 200;
    private static final int SUMMARY_PREVIEW_LENGTH = 240;
    /**
     * 单 run 在整会话历史聚合里最多保留的投影帧数。
     * 长会话的 replayFrames 无界增长会拖垮整包响应；超出部分仍可从
     * {@link #queryRunReplay(String)} 单 run 端点按需补拉。
     */
    private static final int MAX_REPLAY_FRAMES_PER_RUN = 500;

    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final ReplayProjector replayProjector;
    private final HistoryReplayPrinter historyReplayPrinter;
    private final ITimelineItemRepository timelineItemRepository;

    public ConversationHistoryReplayService(ExecutionLedgerQueryService executionLedgerQueryService,
                                            ReplayProjector replayProjector,
                                            HistoryReplayPrinter historyReplayPrinter) {
        this(executionLedgerQueryService, replayProjector, historyReplayPrinter, null);
    }

    @Autowired
    public ConversationHistoryReplayService(ExecutionLedgerQueryService executionLedgerQueryService,
                                            ReplayProjector replayProjector,
                                            HistoryReplayPrinter historyReplayPrinter,
                                            ObjectProvider<ITimelineItemRepository> timelineItemRepositoryProvider) {
        this.executionLedgerQueryService = executionLedgerQueryService;
        this.replayProjector = replayProjector;
        this.historyReplayPrinter = historyReplayPrinter;
        this.timelineItemRepository = timelineItemRepositoryProvider == null
                ? null
                : timelineItemRepositoryProvider.getIfAvailable();
    }

    /**
     * 读取会话轻量摘要。该路径只访问 session/run 主账本，不触发 replay projector、
     * tool output reader 或 artifact 详情查询。
     */
    public ConversationHistoryPage queryConversationHistoryPage(String sessionId,
                                                                 Integer limit,
                                                                 String after) {
        if (StringUtils.isBlank(sessionId) || executionLedgerQueryService == null) {
            return null;
        }
        DialogueSessionView session = executionLedgerQueryService.querySessionHistorySummary(sessionId);
        if (session == null) {
            return null;
        }
        return buildConversationHistoryPage(session, limit, after);
    }

    /**
     * 归属校验通过后读取会话摘要；非 owner 返回 {@code null}。
     * <p>面向用户入口应使用本重载；无 userId 的版本仅供已完成归属校验的内部调用方。</p>
     */
    public ConversationHistoryPage queryConversationHistoryPage(String userId,
                                                                 String sessionId,
                                                                 Integer limit,
                                                                 String after) {
        if (StringUtils.isBlank(sessionId) || executionLedgerQueryService == null) {
            return null;
        }
        DialogueSessionView session = executionLedgerQueryService.querySessionHistorySummary(userId, sessionId);
        if (session == null) {
            return null;
        }
        return buildConversationHistoryPage(session, limit, after);
    }

    private ConversationHistoryPage buildConversationHistoryPage(DialogueSessionView session,
                                                                 Integer limit,
                                                                 String after) {
        String sessionId = session.getSessionId();
        int pageSize = normalizeHistoryPageSize(limit);
        RunCursor cursor = StringUtils.isBlank(after) ? null : RunCursor.decode(after);
        if (cursor != null && !cursor.matchesSession(sessionId)) {
            throw new IllegalArgumentException("cursor 不属于当前 session");
        }

        List<DialogueRunView> pageRuns = executionLedgerQueryService.querySessionRuns(
                sessionId,
                cursor,
                pageSize + 1
        );
        boolean hasMore = pageRuns.size() > pageSize;
        if (hasMore) {
            pageRuns = new ArrayList<>(pageRuns.subList(0, pageSize));
        }

        List<Long> runIds = pageRuns.stream()
                .filter(run -> run != null && run.getId() != null)
                .map(DialogueRunView::getId)
                .toList();
        Map<Long, ContextUsagePayload> contextUsageByRunId =
                executionLedgerQueryService.queryRunContextUsage(runIds);
        DialogueRunView latestRun = resolveLatestRun(session, pageRuns);
        return ConversationHistoryPage.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(resolveSessionStatus(session, pageRuns))
                .collaborationMode(resolveCollaborationMode(session, latestRun))
                .mainAgentKey(session.getMainAgentKey())
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryPreview(preview(session.getLatestQueryText()))
                .latestSummaryPreview(preview(session.getLatestSummaryText()))
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .runs(pageRuns.stream()
                        .map(run -> toRunSummary(run, contextUsageByRunId.get(run.getId())))
                        .toList())
                .nextCursor(hasMore && !pageRuns.isEmpty()
                        ? RunCursor.from(pageRuns.get(pageRuns.size() - 1)).encode()
                        : null)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 单 run 完整回放。只有该路径允许加载账本明细、rich output 和 artifact，
     * 最终事件仍由现有 ReplayProjector/HistoryReplayPrinter 生成。
     */
    public ConversationRunReplay queryRunReplay(String requestId) {
        if (StringUtils.isBlank(requestId) || executionLedgerQueryService == null) {
            return null;
        }
        return buildRunReplay(executionLedgerQueryService.queryRunDetail(requestId));
    }

    /**
     * 归属校验通过后回放单个 run；非 owner 返回 {@code null}。
     */
    public ConversationRunReplay queryRunReplay(String userId, String requestId) {
        if (StringUtils.isBlank(requestId) || executionLedgerQueryService == null) {
            return null;
        }
        return buildRunReplay(executionLedgerQueryService.queryRunDetail(userId, requestId));
    }

    private ConversationRunReplay buildRunReplay(ExecutionRunDetail runDetail) {
        if (runDetail == null || runDetail.getRun() == null) {
            return null;
        }
        DialogueRunView run = runDetail.getRun();
        if (run.getArtifactSummaries() == null && runDetail.getArtifacts() != null) {
            run.setArtifactSummaries(runDetail.getArtifacts());
        }
        ReplayFactBundle bundle = ReplayFactBundle.builder()
                .run(run)
                .llmInvocations(runDetail.getLlmInvocations())
                .toolInvocations(runDetail.getToolInvocations())
                .artifacts(runDetail.getArtifacts())
                .build();
        List<AgentStreamResult> frames =
                replayProjector == null ? List.of() : replayProjector.projectHistoryFrames(bundle);
        List<AgentStreamResult> readableFrames = historyReplayPrinter == null
                ? frames
                : historyReplayPrinter.ensureReadableConclusion(run, frames);
        Set<String> runToolCallIds = new HashSet<>();
        if (runDetail.getToolInvocations() != null) {
            runDetail.getToolInvocations().forEach(invocation -> {
                if (invocation != null && StringUtils.isNotBlank(invocation.getToolCallId())) {
                    runToolCallIds.add(invocation.getToolCallId());
                }
            });
        }
        Map<Long, ContextUsagePayload> contextUsageByRunId =
                queryContextUsage(List.of(run));
        return ConversationRunReplay.builder()
                .run(run)
                .contextUsage(run.getId() == null ? null : contextUsageByRunId.get(run.getId()))
                .replayFrames(readableFrames)
                .timelineItems(buildTimelineItems(
                        run, readableFrames, runDetail.getArtifacts(), runToolCallIds))
                .build();
    }

    private List<TimelineItemView> buildTimelineItems(DialogueRunView run,
                                                      List<AgentStreamResult> frames,
                                                      List<ArtifactView> artifacts,
                                                      Set<String> runToolCallIds) {
        if (run == null || StringUtils.isBlank(run.getRequestId())) {
            return List.of();
        }
        List<TimelineItemView> views = new ArrayList<>();
        long seq = sequenceBase(run);
        int frameIndex = 0;
        for (AgentStreamResult frame : frames == null ? List.<AgentStreamResult>of() : frames) {
            int currentFrameIndex = frameIndex++;
            Map<String, Object> eventData = readCanonicalEventData(frame);
            if (eventData.isEmpty()) {
                continue;
            }
            String parentToolCallId = resolveParentToolCallId(eventData);
            if (TimelineItemProjection.isNestedSubAgentDetail(eventData)
                    && runToolCallIds.contains(parentToolCallId)) {
                // 当前 run 里的子 Agent 明细只属于子会话时间线；父 run 只保留摘要卡。
                continue;
            }
            try {
                TimelineItemView view = TimelineItemProjection.projectEvent(
                        run.getRequestId(),
                        run.getSessionId(),
                        ++seq,
                        eventData,
                        frame.getStatus(),
                        frame.isComplete()
                );
                // history 的 seq 是投影排序号，不是 transport 续流游标。
                view.setEventSeq(0L);
                detachParentOutsideRun(view, runToolCallIds);
                views.add(view);
            } catch (RuntimeException e) {
                // 目录/时间线是回放增强；单帧投影失败不能丢掉其他历史事实。
                log.warn("history timeline projection failed, requestId={}, frameIndex={}",
                        run.getRequestId(), currentFrameIndex, e);
            }
        }
        List<TimelineItem> directory = loadDirectory(run.getRequestId());
        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                views, directory == null ? List.of() : directory);
        TimelineFactAssembler.enrich(merged, run, artifacts);
        return merged;
    }

    /**
     * 子会话里工具账本的 parent_tool_call_id 指向父会话的 Agent 卡。
     * 父卡不在这个 run 的目录里，保留外键会让前端把整组子工具挂进不存在的父节点。
     */
    private void detachParentOutsideRun(TimelineItemView view, Set<String> runToolCallIds) {
        if (view == null || StringUtils.isBlank(view.getParentItemId())) {
            return;
        }
        if (StringUtils.startsWith(view.getParentItemId(), "tool:")) {
            String parentToolCallId = StringUtils.substringAfter(view.getParentItemId(), "tool:");
            if (!runToolCallIds.contains(parentToolCallId)) {
                view.setParentItemId(null);
            }
        }
    }

    /**
     * 历史回放没有 live SSE seq，不能从 1 重新编号：eventId/updatedSeq 是会话级游标，
     * 多轮 run 会撞成相同值。用 run 主键分配稳定区间，并把同一 run 内事件顺序编码进去。
     */
    private long sequenceBase(DialogueRunView run) {
        Long runId = run == null ? null : run.getId();
        if (runId != null && runId > 0 && runId <= Long.MAX_VALUE / 1_000_000L) {
            return runId * 1_000_000L;
        }
        if (run != null && run.getStartedAt() != null) {
            return run.getStartedAt()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli() * 1_000L;
        }
        return 0L;
    }

    private String resolveParentToolCallId(Map<String, Object> eventData) {
        Map<String, Object> task = firstMap(eventData.get("resultMap"), eventData);
        Map<String, Object> nested = firstMap(task.get("resultMap"), Map.of());
        return firstText(
                task.get("parentToolUseId"),
                task.get("parentToolCallId"),
                nested.get("parentToolUseId"),
                nested.get("parentToolCallId"),
                eventData.get("parentToolUseId"),
                eventData.get("parentToolCallId")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object first, Object fallback) {
        if (first instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (fallback instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCanonicalEventData(AgentStreamResult frame) {
        if (frame == null || frame.getEventData() == null) {
            return Map.of();
        }
        Object eventData = frame.getEventData().get("eventData");
        if (eventData instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return frame.getEventData();
    }

    private List<TimelineItem> loadDirectory(String requestId) {
        if (timelineItemRepository == null) {
            return List.of();
        }
        try {
            return timelineItemRepository.queryByRunIds(List.of(requestId));
        } catch (Exception e) {
            log.warn("timeline directory query failed, requestId={}", requestId, e);
            return null;
        }
    }

    public ConversationHistoryDetail queryConversationHistory(String sessionId) {
        if (StringUtils.isBlank(sessionId) || executionLedgerQueryService == null) {
            return null;
        }
        DialogueSessionView session = executionLedgerQueryService.querySession(sessionId);
        if (session == null) {
            return null;
        }
        // session 只提供会话头，具体 run/LLM/tool/artifact 事实由 projector 统一组装。
        List<DialogueRunView> runs = new ArrayList<>();
        List<ExecutionRunDetail> batchRunDetails = new ArrayList<>();
        int offset = 0;
        final int pageSize = ExecutionLedgerQueryService.DEFAULT_SESSION_RUN_PAGE_SIZE;
        boolean truncated = false;
        while (runs.size() < MAX_HISTORY_RUN_COUNT) {
            int remaining = MAX_HISTORY_RUN_COUNT - runs.size();
            int pageLimit = Math.min(pageSize, remaining);
            List<DialogueRunView> page = executionLedgerQueryService.querySessionRuns(sessionId, offset, pageLimit);
            if (CollectionUtils.isEmpty(page)) {
                break;
            }
            runs.addAll(page);
            // 每页批量补齐事实，避免逐 run 调 queryRunDetail，也避免一次读取无界 run 列表。
            batchRunDetails.addAll(executionLedgerQueryService.queryRunDetails(page));
            if (page.size() < pageLimit) {
                break;
            }
            offset += pageLimit;
            truncated = runs.size() >= MAX_HISTORY_RUN_COUNT;
        }
        if (truncated) {
            log.warn("conversationHistory hit run cap sessionId={} cap={}；请改用分页历史接口",
                    sessionId, MAX_HISTORY_RUN_COUNT);
        }
        List<ConversationHistoryDetail.ConversationRunDetail> runDetails = new ArrayList<>();
        Map<Long, ContextUsagePayload> contextUsageByRunId = queryContextUsage(runs);
        // 按 requestId 关联批量明细，避免依赖 queryRunDetails 的返回顺序造成跨 run 串数据。
        Map<String, ExecutionRunDetail> runDetailByRequestId = new HashMap<>();
        for (ExecutionRunDetail detail : batchRunDetails) {
            if (detail == null || detail.getRun() == null
                    || StringUtils.isBlank(detail.getRun().getRequestId())) {
                continue;
            }
            runDetailByRequestId.putIfAbsent(detail.getRun().getRequestId(), detail);
        }
        if (CollectionUtils.isNotEmpty(runs)) {
            for (DialogueRunView run : runs) {
                if (run == null || StringUtils.isBlank(run.getRequestId())) {
                    continue;
                }
                ExecutionRunDetail runDetail = runDetailByRequestId.get(run.getRequestId());
                ReplayFactBundle bundle = ReplayFactBundle.builder()
                        .run(runDetail == null ? run : runDetail.getRun())
                        .llmInvocations(runDetail == null ? List.of() : runDetail.getLlmInvocations())
                        .toolInvocations(runDetail == null ? List.of() : runDetail.getToolInvocations())
                        .artifacts(runDetail == null ? List.of() : runDetail.getArtifacts())
                        .build();
                List<AgentStreamResult> replayFrames = capReplayFrames(replayProjector == null
                        ? List.of()
                        : replayProjector.projectHistoryFrames(bundle));
                runDetails.add(ConversationHistoryDetail.ConversationRunDetail.builder()
                        .requestId(run.getRequestId())
                        .status(run.getStatus())
                        .queryText(run.getQueryText())
                        .finalSummaryText(run.getFinalSummaryText())
                        .startedAt(run.getStartedAt())
                        .finishedAt(run.getFinishedAt())
                        .contextUsage(run.getId() == null ? null : contextUsageByRunId.get(run.getId()))
                        .replayFrames(historyReplayPrinter == null
                                ? replayFrames
                                : historyReplayPrinter.ensureReadableConclusion(run, replayFrames))
                        .build());
            }
        }

        DialogueRunView latestRun = CollectionUtils.isEmpty(runs)
                ? null
                : runs.get(runs.size() - 1);
        return ConversationHistoryDetail.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(resolveSessionStatus(session, runs))
                .collaborationMode(resolveCollaborationMode(session, latestRun))
                .mainAgentKey(session.getMainAgentKey())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .runs(runDetails)
                .build();
    }

    private int normalizeHistoryPageSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_HISTORY_PAGE_SIZE;
        }
        return Math.min(limit, MAX_HISTORY_PAGE_SIZE);
    }

    private DialogueRunView resolveLatestRun(DialogueSessionView session,
                                             List<DialogueRunView> pageRuns) {
        String latestRequestId = session == null ? null : session.getLatestRequestId();
        if (StringUtils.isNotBlank(latestRequestId)) {
            // 首页通常已经包含最新 run；先复用页面内对象，只有跨页时才补一次摘要查询。
            if (CollectionUtils.isNotEmpty(pageRuns)) {
                for (DialogueRunView run : pageRuns) {
                    if (run != null && latestRequestId.equals(run.getRequestId())) {
                        return run;
                    }
                }
            }
            DialogueRunView latest = executionLedgerQueryService.queryRunSummary(latestRequestId);
            if (latest != null) {
                return latest;
            }
        }
        return CollectionUtils.isEmpty(pageRuns) ? null : pageRuns.get(pageRuns.size() - 1);
    }

    private ConversationRunSummary toRunSummary(DialogueRunView run) {
        return toRunSummary(run, null);
    }

    private ConversationRunSummary toRunSummary(DialogueRunView run, ContextUsagePayload contextUsage) {
        if (run == null) {
            return null;
        }
        return ConversationRunSummary.builder()
                .requestId(run.getRequestId())
                .entryAgent(run.getEntryAgent())
                .status(run.getStatus())
                .queryPreview(preview(run.getQueryText()))
                .finalSummaryPreview(preview(run.getFinalSummaryText()))
                .finalSummaryText(run.getFinalSummaryText())
                .contextUsage(contextUsage)
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .hasReplay(StringUtils.isNotBlank(run.getRequestId()))
                .build();
    }

    private List<AgentStreamResult> capReplayFrames(List<AgentStreamResult> frames) {
        if (frames == null) {
            return List.of();
        }
        if (frames.size() <= MAX_REPLAY_FRAMES_PER_RUN) {
            return frames;
        }
        // 保留时间线前段；结论帧由 ensureReadableConclusion 在截断后补齐，不会被砍掉。
        return new ArrayList<>(frames.subList(0, MAX_REPLAY_FRAMES_PER_RUN));
    }

    private String preview(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return StringUtils.abbreviate(StringUtils.trim(value), SUMMARY_PREVIEW_LENGTH);
    }

    private String resolveCollaborationMode(DialogueSessionView session, DialogueRunView run) {
        if (session != null && StringUtils.isNotBlank(session.getCollaborationMode())) {
            return org.wwz.ai.domain.agent.runtime.enums.CollaborationMode
                    .normalize(session.getCollaborationMode());
        }
        return run != null && ExecutionLedgerConstants.ENTRY_AGENT_PLAN_SOLVE.equals(
                StringUtils.trimToEmpty(run.getEntryAgent()))
                ? org.wwz.ai.domain.agent.runtime.enums.CollaborationMode.CLUSTER_VALUE
                : org.wwz.ai.domain.agent.runtime.enums.CollaborationMode.NORMAL_VALUE;
    }

    private Map<Long, ContextUsagePayload> queryContextUsage(List<DialogueRunView> runs) {
        if (executionLedgerQueryService == null || CollectionUtils.isEmpty(runs)) {
            return Map.of();
        }
        List<Long> runIds = runs.stream()
                .filter(run -> run != null && run.getId() != null)
                .map(DialogueRunView::getId)
                .distinct()
                .toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ContextUsagePayload> usage = executionLedgerQueryService.queryRunContextUsage(runIds);
        return usage == null ? Map.of() : usage;
    }

    /**
     * 会话历史对外复用 run 的整型状态，保持与账本一致，
     * 具体的字符串化交给 trigger 层统一收口，避免多个层次重复维护枚举。
     */
    private Integer resolveSessionStatus(DialogueSessionView session, List<DialogueRunView> runs) {
        if (session != null && session.getStatus() != null) {
            return session.getStatus();
        }
        if (CollectionUtils.isEmpty(runs)) {
            return ExecutionLedgerConstants.STATUS_RUNNING;
        }
        DialogueRunView latestRun = runs.get(runs.size() - 1);
        return latestRun == null || latestRun.getStatus() == null
                ? ExecutionLedgerConstants.STATUS_RUNNING
                : latestRun.getStatus();
    }

}
