package org.wwz.ai.test.domain;

import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.LlmUsageSummary;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.WorkingMemoryConversationRow;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.impl.AgentExecutionRecorderImpl;
import org.wwz.ai.domain.agent.ledger.impl.ExecutionLedgerQueryServiceImpl;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.domain.agent.ledger.replay.HistoryReplayPrinter;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.CodeInterpreterToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DataAnalysisToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DeepSearchToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.ImageGenerationToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.MultiModalToolInvocationProjector;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.ledger.timeline.LedgerTimelineProjectionWriter;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputReader;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputWriter;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkExecutionEvent;
import org.wwz.ai.infrastructure.adapter.repository.ExecutionLedgerReadRepository;
import org.wwz.ai.infrastructure.adapter.repository.ExecutionLedgerWriteRepository;
import org.wwz.ai.infrastructure.dao.cute.IArtifactLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueRunLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IDialogueSessionLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.ILlmInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IToolInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryCompactionReadDao;
import org.wwz.ai.infrastructure.dao.cute.ILtmForkExecutionReadDao;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryConversationReadDao;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行账本测试夹具。
 * 使用内存 DAO 替代真实 MyBatis/数据库，便于在 application-test 禁库配置下做纯单元回归。
 */
public final class ExecutionLedgerFixtureFactory {

    private ExecutionLedgerFixtureFactory() {
    }

    static LedgerTestContext newLedgerTestContext() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        InMemoryDialogueRunLedgerDao runDao = new InMemoryDialogueRunLedgerDao(store);
        InMemoryDialogueSessionLedgerDao sessionDao = new InMemoryDialogueSessionLedgerDao(store);
        InMemoryLlmInvocationLedgerDao llmDao = new InMemoryLlmInvocationLedgerDao(store);
        InMemoryToolInvocationLedgerDao toolDao = new InMemoryToolInvocationLedgerDao(store);
        InMemoryArtifactLedgerDao artifactDao = new InMemoryArtifactLedgerDao(store);
        InMemoryTimelineItemRepository timelineItemRepository =
                new InMemoryTimelineItemRepository(store);
        InMemoryWorkingMemoryCompactionReadDao compactionReadDao =
                new InMemoryWorkingMemoryCompactionReadDao(store);
        InMemoryLtmForkExecutionReadDao ltmForkReadDao =
                new InMemoryLtmForkExecutionReadDao(store);
        InMemoryWorkingMemoryConversationReadDao conversationReadDao =
                new InMemoryWorkingMemoryConversationReadDao(store);
        InMemoryToolOutputWriter toolOutputWriter = new InMemoryToolOutputWriter(store);
        InMemoryToolOutputReader toolOutputReader = new InMemoryToolOutputReader(store);
        ExecutionLedgerReadRepository readRepository = new ExecutionLedgerReadRepository(
                runDao, sessionDao, llmDao, toolDao, artifactDao,
                compactionReadDao, ltmForkReadDao, conversationReadDao
        );
        ExecutionLedgerWriteRepository writeRepository = new ExecutionLedgerWriteRepository(
                runDao, sessionDao, llmDao, toolDao, artifactDao
        );
        ExecutionLedgerQueryServiceImpl queryService = new ExecutionLedgerQueryServiceImpl(
                readRepository, toolOutputReader
        );
        ConversationHistoryReplayService replayService = new ConversationHistoryReplayService(
                queryService,
                new ReplayProjector(
                        new ToolInvocationProjectorRegistry(
                                List.of(
                                        new CodeInterpreterToolInvocationProjector(),
                                        new DataAnalysisToolInvocationProjector(),
                                        new DeepSearchToolInvocationProjector(),
                                        new MultiModalToolInvocationProjector(),
                                        new ImageGenerationToolInvocationProjector(),
                                        new DefaultToolInvocationProjector()
                                ),
                                new DefaultToolInvocationProjector()
                        )
                ),
                new HistoryReplayPrinter(),
                null
        );
        AgentExecutionRecorder recorder = new AgentExecutionRecorderImpl(
                writeRepository,
                toolOutputWriter,
                new LedgerTimelineProjectionWriter(timelineItemRepository, writeRepository)
        );
        return new LedgerTestContext(
                store, recorder, queryService, replayService,
                readRepository, writeRepository,
                runDao, sessionDao, llmDao, toolDao, artifactDao,
                compactionReadDao, ltmForkReadDao, conversationReadDao,
                toolOutputWriter, toolOutputReader, timelineItemRepository
        );
    }

    static AgentContext newAgentContext(String requestId, String sessionId, AgentExecutionRecorder recorder) {
        return newAgentContext(requestId, sessionId, "测试执行账本", recorder);
    }

    static AgentContext newAgentContext(String requestId,
                                        String sessionId,
                                        String query,
                                        AgentExecutionRecorder recorder) {
        ToolCollection toolCollection = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .query(query)
                .toolCollection(toolCollection)
                .executionRecorder(recorder)
                .build();
        toolCollection.setAgentContext(context);
        return context;
    }

    static Long activateRun(AgentContext context, AgentExecutionRecorder recorder, String entryAgent) {
        return activateRun(context, recorder, entryAgent, null);
    }

    static Long activateRun(AgentContext context,
                            AgentExecutionRecorder recorder,
                            String entryAgent,
                            String userId) {
        Long runId = recorder.createRun(org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord.builder()
                .runUid(context.getRequestId())
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .userId(userId)
                .entryAgent(entryAgent)
                .queryText(context.getQuery())
                .startedAt(LocalDateTime.now())
                .build());
        context.activateLedgerRun(runId, context.getRequestId());
        return runId;
    }

    static Long createLlmInvocation(AgentContext context,
                                    AgentExecutionRecorder recorder,
                                    String agentName,
                                    Integer stepNo,
                                    String callKind) {
        return createLlmInvocation(context, recorder, agentName, stepNo, callKind,
                false, LocalDateTime.now(), "test-model");
    }

    static Long createLlmInvocation(AgentContext context,
                                    AgentExecutionRecorder recorder,
                                    String agentName,
                                    Integer stepNo,
                                    String callKind,
                                    boolean streaming,
                                    LocalDateTime startedAt,
                                    String modelName) {
        context.markExecutionPosition(agentName, stepNo);
        Long llmInvocationId = recorder.createLlmInvocation(LlmInvocationStartRecord.builder()
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .invocationSeq(context.getAgentRunState().nextInvocationSeq())
                .agentName(agentName)
                .stepNo(stepNo)
                .callKind(callKind)
                .streaming(streaming)
                .modelName(modelName)
                .startedAt(startedAt)
                .build());
        context.getAgentRunState().bindCurrentLlmInvocationId(llmInvocationId);
        return llmInvocationId;
    }

    static ToolCall newToolCall(String id, String toolName, String arguments) {
        return ToolCall.builder()
                .id(id)
                .type("function")
                .function(ToolCall.Function.builder()
                        .name(toolName)
                        .arguments(arguments)
                        .build())
                .build();
    }

    static File newFile(String fileName, String url, boolean internalFile) {
        return File.builder()
                .fileName(fileName)
                .ossUrl(url)
                .domainUrl(url)
                .isInternalFile(internalFile)
                .build();
    }

    static final class LedgerTestContext {
        final InMemoryLedgerStore store;
        final AgentExecutionRecorder recorder;
        final ExecutionLedgerQueryService queryService;
        final ConversationHistoryReplayService replayService;
        final ExecutionLedgerReadRepository readRepository;
        final ExecutionLedgerWriteRepository writeRepository;
        final IDialogueRunLedgerDao runDao;
        final IDialogueSessionLedgerDao sessionDao;
        final ILlmInvocationLedgerDao llmDao;
        final IToolInvocationLedgerDao toolDao;
        final IArtifactLedgerDao artifactDao;
        final IWorkingMemoryCompactionReadDao compactionReadDao;
        final ILtmForkExecutionReadDao ltmForkReadDao;
        final IWorkingMemoryConversationReadDao conversationReadDao;
        final ToolOutputWriter toolOutputWriter;
        final ToolOutputReader toolOutputReader;
        final ITimelineItemRepository timelineItemRepository;

        private LedgerTestContext(InMemoryLedgerStore store,
                                  AgentExecutionRecorder recorder,
                                  ExecutionLedgerQueryService queryService,
                                  ConversationHistoryReplayService replayService,
                                  ExecutionLedgerReadRepository readRepository,
                                  ExecutionLedgerWriteRepository writeRepository,
                                  IDialogueRunLedgerDao runDao,
                                  IDialogueSessionLedgerDao sessionDao,
                                  ILlmInvocationLedgerDao llmDao,
                                  IToolInvocationLedgerDao toolDao,
                                  IArtifactLedgerDao artifactDao,
                                  IWorkingMemoryCompactionReadDao compactionReadDao,
                                  ILtmForkExecutionReadDao ltmForkReadDao,
                                  IWorkingMemoryConversationReadDao conversationReadDao,
                                  ToolOutputWriter toolOutputWriter,
                                  ToolOutputReader toolOutputReader,
                                  ITimelineItemRepository timelineItemRepository) {
            this.store = store;
            this.recorder = recorder;
            this.queryService = queryService;
            this.replayService = replayService;
            this.readRepository = readRepository;
            this.writeRepository = writeRepository;
            this.runDao = runDao;
            this.sessionDao = sessionDao;
            this.llmDao = llmDao;
            this.toolDao = toolDao;
            this.artifactDao = artifactDao;
            this.compactionReadDao = compactionReadDao;
            this.ltmForkReadDao = ltmForkReadDao;
            this.conversationReadDao = conversationReadDao;
            this.toolOutputWriter = toolOutputWriter;
            this.toolOutputReader = toolOutputReader;
            this.timelineItemRepository = timelineItemRepository;
        }
    }

    /**
     * 共享内存账本存储。
     */
    static final class InMemoryLedgerStore {
        long nextRunId = 1L;
        long nextSessionId = 1L;
        long nextLlmId = 1L;
        long nextToolId = 1L;
        long nextArtifactId = 1L;
        long nextTimelineItemId = 1L;
        Map<Long, DialogueSession> sessions = new LinkedHashMap<>();
        /** bindingId → owner_user_id，对应生产 SQL 里对 ai_agent_channel_binding 的子查询。 */
        Map<Long, String> bindingOwners = new LinkedHashMap<>();
        Map<Long, DialogueRun> runs = new LinkedHashMap<>();
        Map<Long, LlmInvocation> llmInvocations = new LinkedHashMap<>();
        Map<Long, ToolInvocation> toolInvocations = new LinkedHashMap<>();
        Map<Long, ArtifactRecord> artifacts = new LinkedHashMap<>();
        Map<String, TimelineItem> timelineItems = new LinkedHashMap<>();
        Map<Long, WorkingMemoryCompactionEvent> compactionEvents = new LinkedHashMap<>();
        Map<Long, LtmForkExecutionEvent> ltmForkEvents = new LinkedHashMap<>();
        List<WorkingMemoryConversationRow> conversationRows = new ArrayList<>();
        Map<String, Map<Long, ToolOutputView>> toolOutputsByToolAndInvocationId = new LinkedHashMap<>();
        Map<String, Map<String, ToolOutputView>> toolOutputsByToolAndDirectKey = new LinkedHashMap<>();
        int queryRunByRequestIdCount;
        int queryLlmByRunIdCount;
        int queryLlmByRunIdsCount;
        int queryToolByRunIdCount;
        int queryToolByRunIdsCount;
        int queryArtifactsByRunIdCount;
        int queryArtifactsByRunIdsCount;
        int queryArtifactsBySessionIdCount;
        int queryCompactionByRequestIdsCount;
        int queryMemoryForkByRequestIdsCount;
        int queryConversationBySessionIdsCount;
        boolean failConversationRead;
        int readToolOutputByInvocationIdCount;
        int readToolOutputByInvocationIdsCount;
    }

    static final class InMemoryTimelineItemRepository implements ITimelineItemRepository {
        private final InMemoryLedgerStore store;

        private InMemoryTimelineItemRepository(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int upsert(TimelineItem item) {
            if (item == null || isBlank(item.getSessionId()) || isBlank(item.getItemId())) {
                return 0;
            }
            String key = item.getSessionId() + ":" + item.getItemId();
            TimelineItem existing = store.timelineItems.get(key);
            if (existing == null) {
                item.setId(store.nextTimelineItemId++);
                item.setCreateTime(LocalDateTime.now());
                item.setUpdateTime(item.getCreateTime());
                item.setDeleted(0);
                store.timelineItems.put(key, item);
                return 1;
            }
            if (item.getParentItemId() != null) {
                existing.setParentItemId(item.getParentItemId());
            }
            if (item.getOrderKey() != null
                    && (existing.getOrderKey() == null || existing.getOrderKey() == 0)) {
                existing.setOrderKey(item.getOrderKey());
            }
            if (item.getSubOrder() != null
                    && (existing.getSubOrder() == null || existing.getSubOrder() == 0)) {
                existing.setSubOrder(item.getSubOrder());
            }
            if (item.getStatus() != null) {
                existing.setStatus(item.getStatus());
            }
            existing.setUpdatedSeq(Math.max(
                    existing.getUpdatedSeq() == null ? 0L : existing.getUpdatedSeq(),
                    item.getUpdatedSeq() == null ? 0L : item.getUpdatedSeq()));
            existing.setUpdateTime(LocalDateTime.now());
            existing.setDeleted(0);
            return 1;
        }

        @Override
        public int upsertBatch(List<TimelineItem> items) {
            if (items == null || items.isEmpty()) {
                return 0;
            }
            int affected = 0;
            for (TimelineItem item : items) {
                affected += upsert(item);
            }
            return affected;
        }

        @Override
        public TimelineItem queryBySessionAndItemId(String sessionId, String itemId) {
            if (isBlank(sessionId) || isBlank(itemId)) {
                return null;
            }
            TimelineItem item = store.timelineItems.get(sessionId + ":" + itemId);
            return item != null && (item.getDeleted() == null || item.getDeleted() == 0)
                    ? item
                    : null;
        }

        @Override
        public List<TimelineItem> queryByRunIds(List<String> runIds) {
            if (runIds == null || runIds.isEmpty()) {
                return List.of();
            }
            return store.timelineItems.values().stream()
                    .filter(item -> item.getDeleted() == null || item.getDeleted() == 0)
                    .filter(item -> runIds.contains(item.getRunId()))
                    .sorted(Comparator
                            .comparingLong((TimelineItem item) ->
                                    item.getOrderKey() == null ? 0L : item.getOrderKey())
                            .thenComparingInt(item ->
                                    item.getSubOrder() == null ? 0 : item.getSubOrder())
                            .thenComparing(TimelineItem::getItemId))
                    .toList();
        }
    }

    static final class InMemoryToolOutputWriter implements ToolOutputWriter {
        private final InMemoryLedgerStore store;

        private InMemoryToolOutputWriter(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public void write(ToolOutputPersistCommand command) {
            writeInternal(command, false);
        }

        @Override
        public void writeOrThrow(ToolOutputPersistCommand command) {
            writeInternal(command, true);
        }

        private void writeInternal(ToolOutputPersistCommand command, boolean strict) {
            if (command == null || command.getStructuredOutput() == null) {
                return;
            }
            String toolName = resolveToolName(command);
            if (isBlank(toolName)
                    || isRetiredToolOutput(toolName)
                    || isBlank(command.getRequestId())
                    || isBlank(command.getToolCallId())) {
                return;
            }
            String directKey = buildDirectKey(command.getRequestId(), command.getToolCallId());
            Map<String, ToolOutputView> directOutputs = store.toolOutputsByToolAndDirectKey
                    .computeIfAbsent(toolName, key -> new LinkedHashMap<>());
            if (command.getToolInvocationId() != null) {
                Map<Long, ToolOutputView> invocationOutputs = store.toolOutputsByToolAndInvocationId
                        .computeIfAbsent(toolName, key -> new LinkedHashMap<>());
                if (invocationOutputs.containsKey(command.getToolInvocationId())) {
                    if (strict) {
                        throw new IllegalStateException("duplicate tool invocation id");
                    }
                    return;
                }
            }
            if (directOutputs.containsKey(directKey)) {
                if (strict) {
                    throw new IllegalStateException("duplicate request/toolCallId");
                }
                return;
            }
            ToolOutputView view = ToolOutputView.builder()
                    .toolName(toolName)
                    .requestId(command.getRequestId())
                    .requestSource(command.getRequestSource() == null ? ExecutionLedgerConstants.REQUEST_SOURCE_AGENT : command.getRequestSource())
                    .sessionId(command.getSessionId())
                    .toolCallId(command.getToolCallId())
                    .status(command.getStatus())
                    .errorMsg(command.getErrorMsg())
                    .createdAt(LocalDateTime.now())
                    .structuredOutput(command.getStructuredOutput())
                    .build();
            if (command.getToolInvocationId() != null) {
                store.toolOutputsByToolAndInvocationId
                        .computeIfAbsent(toolName, key -> new LinkedHashMap<>())
                        .put(command.getToolInvocationId(), cloneOutputView(view));
            }
            directOutputs.put(directKey, cloneOutputView(view));
        }

        private String resolveToolName(ToolOutputPersistCommand command) {
            if (command.getToolName() != null && !command.getToolName().isBlank()) {
                return command.getToolName();
            }
            return command.getStructuredOutput() == null ? null : command.getStructuredOutput().getToolName();
        }

        private boolean isRetiredToolOutput(String toolName) {
            return "file_tool".equals(toolName)
                    || "planning".equals(toolName)
                    || "report_tool".equals(toolName)
                    || "script_runner_tool".equals(toolName);
        }
    }

    static final class InMemoryToolOutputReader implements ToolOutputReader {
        private final InMemoryLedgerStore store;

        private InMemoryToolOutputReader(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public java.util.Optional<ToolStructuredOutput> readByInvocationId(String toolName, Long toolInvocationId) {
            store.readToolOutputByInvocationIdCount++;
            if (isBlank(toolName) || toolInvocationId == null) {
                return java.util.Optional.empty();
            }
            Map<Long, ToolOutputView> outputs = store.toolOutputsByToolAndInvocationId.get(toolName);
            ToolOutputView view = outputs == null ? null : outputs.get(toolInvocationId);
            return java.util.Optional.ofNullable(view == null ? null : view.getStructuredOutput());
        }

        @Override
        public Map<Long, ToolStructuredOutput> readByInvocationIds(List<ToolInvocationView> invocations,
                                                                   List<ArtifactView> artifacts) {
            store.readToolOutputByInvocationIdsCount++;
            Map<Long, ToolStructuredOutput> outputs = new LinkedHashMap<>();
            if (invocations == null) {
                return outputs;
            }
            for (ToolInvocationView invocation : invocations) {
                if (invocation == null || invocation.getId() == null) {
                    continue;
                }
                Map<Long, ToolOutputView> toolOutputs = store.toolOutputsByToolAndInvocationId
                        .get(invocation.getToolName());
                ToolOutputView output = toolOutputs == null ? null : toolOutputs.get(invocation.getId());
                if (output != null && output.getStructuredOutput() != null) {
                    outputs.put(invocation.getId(), output.getStructuredOutput());
                }
            }
            return outputs;
        }

        @Override
        public java.util.Optional<ToolOutputView> readDirect(String requestId, String toolCallId) {
            if (isBlank(requestId) || isBlank(toolCallId)) {
                return java.util.Optional.empty();
            }
            String directKey = buildDirectKey(requestId, toolCallId);
            List<ToolOutputView> matches = new ArrayList<>();
            for (Map<String, ToolOutputView> outputs : store.toolOutputsByToolAndDirectKey.values()) {
                if (outputs == null) {
                    continue;
                }
                ToolOutputView view = outputs.get(directKey);
                if (view != null) {
                    matches.add(cloneOutputView(view));
                }
            }
            // direct lookup 命中多张输出表时视为冲突，不返回歧义结果。
            if (matches.size() != 1) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(matches.get(0));
        }
    }

    static final class InMemoryDialogueRunLedgerDao implements IDialogueRunLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryDialogueRunLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int insertRun(DialogueRun run) {
            for (DialogueRun existing : store.runs.values()) {
                if (existing.getDeleted() == 0
                        && (existing.getRequestId().equals(run.getRequestId()) || existing.getRunUid().equals(run.getRunUid()))) {
                    throw new IllegalStateException("duplicate run key");
                }
            }
            run.setId(store.nextRunId++);
            run.setCreateTime(LocalDateTime.now());
            run.setUpdateTime(run.getCreateTime());
            run.setDeleted(0);
            store.runs.put(run.getId(), cloneRun(run));
            return 1;
        }

        @Override
        public int updateRunFinish(DialogueRun run) {
            DialogueRun existing = store.runs.get(run.getId());
            if (existing == null) {
                return 0;
            }
            // 与生产 SQL 保持一致：终态（1=成功 2=失败 3=超时 4=停止）不可被覆盖，
            // 返回 0 表示该 run 已经结束过，调用方据此跳过会话计数累加。
            int currentStatus = existing.getStatus() == null ? 0 : existing.getStatus();
            if (currentStatus == 1 || currentStatus == 2 || currentStatus == 3 || currentStatus == 4) {
                return 0;
            }
            existing.setStatus(run.getStatus());
            existing.setFinalSummaryText(run.getFinalSummaryText());
            existing.setLlmCallCount(run.getLlmCallCount());
            existing.setToolCallCount(run.getToolCallCount());
            existing.setArtifactCount(run.getArtifactCount());
            existing.setPromptTokensTotal(run.getPromptTokensTotal());
            existing.setCompletionTokensTotal(run.getCompletionTokensTotal());
            existing.setTotalTokensTotal(run.getTotalTokensTotal());
            existing.setErrorCode(run.getErrorCode());
            existing.setErrorMsg(run.getErrorMsg());
            existing.setFinishedAt(run.getFinishedAt());
            existing.setDurationMs(run.getDurationMs());
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public DialogueRun queryById(Long id) {
            DialogueRun run = store.runs.get(id);
            return run == null || run.getDeleted() != 0 ? null : cloneRun(run);
        }

        @Override
        public List<DialogueRun> queryOrphanedRunningRuns(LocalDateTime startedBefore, int limit) {
            return List.of();
        }

        @Override
        public DialogueRun queryByRequestId(String requestId) {
            store.queryRunByRequestIdCount++;
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRequestId().equals(requestId))
                    .findFirst()
                    .map(ExecutionLedgerFixtureFactory::cloneRun)
                    .orElse(null);
        }

        @Override
        public DialogueRun querySummaryByRequestId(String requestId) {
            return queryByRequestId(requestId);
        }

        @Override
        public List<DialogueRunView> queryRecentBySessionId(String sessionId, int limit) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(DialogueRun::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }

        @Override
        public List<DialogueRunView> queryBySessionId(String sessionId, int offset, int limit) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime)
                            .thenComparing(DialogueRun::getId))
                    .skip(offset)
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }

        @Override
        public List<DialogueRunView> queryByTimeRangeAfter(
                LocalDateTime from,
                LocalDateTime to,
                List<Integer> statuses,
                LocalDateTime afterCreateTime,
                Long afterId,
                int limit) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0)
                    .filter(item -> !item.getCreateTime().isBefore(from)
                            && item.getCreateTime().isBefore(to))
                    .filter(item -> statuses == null
                            || statuses.isEmpty()
                            || statuses.contains(item.getStatus()))
                    .filter(item -> afterCreateTime == null
                            || item.getCreateTime().isAfter(afterCreateTime)
                            || (item.getCreateTime().equals(afterCreateTime)
                            && item.getId() > afterId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime)
                            .thenComparing(DialogueRun::getId))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }

        @Override
        public List<DialogueRunView> queryFirstNonBlankQueriesBySessionIds(List<String> sessionIds) {
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }
            Map<String, DialogueRunView> firstQueries = new LinkedHashMap<>();
            store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && sessionIds.contains(item.getSessionId()))
                    .filter(item -> item.getQueryText() != null && !item.getQueryText().trim().isEmpty())
                    .sorted(Comparator.comparing(DialogueRun::getId))
                    .forEach(item -> firstQueries.putIfAbsent(item.getSessionId(), DialogueRunView.builder()
                            .sessionId(item.getSessionId())
                            .queryText(item.getQueryText())
                            .build()));
            return new ArrayList<>(firstQueries.values());
        }

        @Override
        public List<DialogueRunView> queryBySessionIdAfter(String sessionId,
                                                            LocalDateTime afterCreateTime,
                                                            Long afterId,
                                                            int limit) {
            return store.runs.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .filter(item -> afterCreateTime == null || afterId == null
                            || item.getCreateTime().isAfter(afterCreateTime)
                            || (item.getCreateTime().isEqual(afterCreateTime) && item.getId() > afterId))
                    .sorted(Comparator.comparing(DialogueRun::getCreateTime)
                            .thenComparing(DialogueRun::getId))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toRunView)
                    .toList();
        }

    }

    static final class InMemoryDialogueSessionLedgerDao implements IDialogueSessionLedgerDao {
        private final InMemoryLedgerStore store;
        private long lastInsertId;

        private InMemoryDialogueSessionLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        /** 与生产 SQL 同口径：本人会话，或该用户是渠道绑定 owner 的会话。 */
        private boolean visibleToUser(String userId, DialogueSession session) {
            if (equalsNullable(userId, session.getUserId())) {
                return true;
            }
            String owner = session.getBindingId() == null ? null : store.bindingOwners.get(session.getBindingId());
            return userId != null && userId.equals(owner);
        }

        @Override
        public int upsertSession(DialogueSessionUpsertRecord record) {
            if (record == null || isBlank(record.getSessionId())) {
                return 0;
            }
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && record.getSessionId().equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                session = DialogueSession.builder()
                        .id(store.nextSessionId++)
                        .sessionId(record.getSessionId())
                        // 身份列仅在建行时写入，与生产 SQL 的 ON DUPLICATE 语义一致
                        .bindingId(record.getBindingId())
                        .channelChatType(record.getChannelChatType())
                        .channelTargetId(record.getChannelTargetId())
                        .channelLastMsgId(record.getChannelLastMsgId())
                        .channelLastReceivedAt(record.getChannelLastReceivedAt())
                        .sessionKind(defaultSessionKind(record.getSessionKind()))
                        .parentSessionId(record.getParentSessionId())
                        .rootSessionId(record.getRootSessionId())
                        .subAgentType(record.getSubAgentType())
                        .mainAgentKey(record.getMainAgentKey())
                        .promptVersion(record.getPromptVersion())
                        .promptHash(record.getPromptHash())
                        .promptSnapshot(record.getPromptSnapshot())
                        // 与生产 SQL 的 IFNULL(#{collaborationMode}, 'normal') 一致
                        .collaborationMode(record.getCollaborationMode() == null ? "normal" : record.getCollaborationMode())
                        .createTime(LocalDateTime.now())
                        .deleted(0)
                        .build();
                store.sessions.put(session.getId(), session);
            }
            if (isBlank(session.getUserId())) {
                session.setUserId(record.getUserId());
            }
            session.setChannelChatType(record.getChannelChatType() == null
                    ? session.getChannelChatType() : record.getChannelChatType());
            session.setChannelTargetId(record.getChannelTargetId() == null
                    ? session.getChannelTargetId() : record.getChannelTargetId());
            // 整行写入按 VALUES 覆盖；run 启动的 COALESCE 语义在下面的单独方法里保留。
            session.setChannelLastMsgId(record.getChannelLastMsgId());
            session.setChannelLastReceivedAt(record.getChannelLastReceivedAt());
            session.setTitle(record.getTitle());
            session.setStatus(record.getStatus());
            session.setLatestRequestId(record.getLatestRequestId());
            session.setLatestQueryText(record.getLatestQueryText());
            session.setLatestSummaryText(record.getLatestSummaryText());
            session.setRunCount(record.getRunCount());
            session.setFinishedRunCount(record.getFinishedRunCount());
            session.setFailedRunCount(record.getFailedRunCount());
            session.setStartedAt(record.getStartedAt());
            session.setLastActiveAt(record.getLastActiveAt());
            // 会话偏好列走「未传不清空」，与生产 SQL 的 COALESCE(VALUES(col), col) 一致
            if (record.getModel() != null) {
                session.setModel(record.getModel());
            }
            if (record.getThinking() != null) {
                session.setThinking(record.getThinking());
            }
            if (record.getThinkingEffort() != null) {
                session.setThinkingEffort(record.getThinkingEffort());
            }
            if (record.getCollaborationMode() != null) {
                session.setCollaborationMode(record.getCollaborationMode());
            }
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int insertSessionIfAbsent(DialogueSessionUpsertRecord record) {
            if (record == null || isBlank(record.getSessionId())) {
                return 0;
            }
            DialogueSession existing = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && record.getSessionId().equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return 0;
            }
            DialogueSession session = DialogueSession.builder()
                    .id(store.nextSessionId++)
                    .sessionId(record.getSessionId())
                    .userId(record.getUserId())
                    .bindingId(record.getBindingId())
                    .channelChatType(record.getChannelChatType())
                    .channelTargetId(record.getChannelTargetId())
                    .channelLastMsgId(record.getChannelLastMsgId())
                    .channelLastReceivedAt(record.getChannelLastReceivedAt())
                    .sessionKind(defaultSessionKind(record.getSessionKind()))
                    .parentSessionId(record.getParentSessionId())
                    .rootSessionId(record.getRootSessionId())
                    .subAgentType(record.getSubAgentType())
                        .mainAgentKey(record.getMainAgentKey())
                        .promptVersion(record.getPromptVersion())
                        .promptHash(record.getPromptHash())
                        .promptSnapshot(record.getPromptSnapshot())
                    .title(record.getTitle())
                    .status(record.getStatus())
                    .latestRequestId(record.getLatestRequestId())
                    .latestQueryText(record.getLatestQueryText())
                    .latestSummaryText(record.getLatestSummaryText())
                    .runCount(defaultCount(record.getRunCount()))
                    .finishedRunCount(defaultCount(record.getFinishedRunCount()))
                    .failedRunCount(defaultCount(record.getFailedRunCount()))
                    .startedAt(record.getStartedAt())
                    .lastActiveAt(record.getLastActiveAt())
                    .model(record.getModel())
                    .thinking(record.getThinking())
                    .thinkingEffort(record.getThinkingEffort())
                    .collaborationMode(record.getCollaborationMode() == null ? "normal" : record.getCollaborationMode())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .deleted(0)
                    .build();
            store.sessions.put(session.getId(), session);
            return 1;
        }

        @Override
        public int upsertSessionOnRunStart(DialogueSessionUpsertRecord record) {
            if (record == null || isBlank(record.getSessionId())) {
                return 0;
            }
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && record.getSessionId().equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                session = DialogueSession.builder()
                        .id(store.nextSessionId++)
                        .sessionId(record.getSessionId())
                        .bindingId(record.getBindingId())
                        .channelChatType(record.getChannelChatType())
                        .channelTargetId(record.getChannelTargetId())
                        .channelLastMsgId(record.getChannelLastMsgId())
                        .channelLastReceivedAt(record.getChannelLastReceivedAt())
                        .sessionKind(defaultSessionKind(record.getSessionKind()))
                        .parentSessionId(record.getParentSessionId())
                        .rootSessionId(record.getRootSessionId())
                        .subAgentType(record.getSubAgentType())
                        .mainAgentKey(record.getMainAgentKey())
                        .promptVersion(record.getPromptVersion())
                        .promptHash(record.getPromptHash())
                        .promptSnapshot(record.getPromptSnapshot())
                        .runCount(1)
                        .finishedRunCount(0)
                        .failedRunCount(0)
                        .createTime(LocalDateTime.now())
                        .deleted(0)
                        .build();
                session.setStartedAt(record.getStartedAt());
                store.sessions.put(session.getId(), session);
            } else {
                // 与生产 SQL 一致：已存在则 run_count 原子 +1，不覆盖 started_at。
                session.setRunCount(defaultCount(session.getRunCount()) + 1);
            }
            if (isBlank(session.getUserId())) {
                session.setUserId(record.getUserId());
            }
            session.setChannelChatType(record.getChannelChatType() == null
                    ? session.getChannelChatType() : record.getChannelChatType());
            session.setChannelTargetId(record.getChannelTargetId() == null
                    ? session.getChannelTargetId() : record.getChannelTargetId());
            if (record.getChannelLastMsgId() != null) {
                session.setChannelLastMsgId(record.getChannelLastMsgId());
            }
            if (record.getChannelLastReceivedAt() != null) {
                session.setChannelLastReceivedAt(record.getChannelLastReceivedAt());
            }
            session.setTitle(record.getTitle());
            session.setStatus(record.getStatus());
            session.setLatestRequestId(record.getLatestRequestId());
            session.setLatestQueryText(record.getLatestQueryText());
            session.setLatestSummaryText(record.getLatestSummaryText());
            session.setLastActiveAt(record.getLastActiveAt());
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateSessionPreferences(String sessionId,
                                            String model,
                                            Boolean thinking,
                                            String thinkingEffort,
                                            String collaborationMode) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && sessionId != null
                            && sessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            // 与生产 SQL 的 COALESCE 一致：null 不清空已有值
            if (model != null) {
                session.setModel(model);
            }
            if (thinking != null) {
                session.setThinking(thinking);
            }
            if (thinkingEffort != null) {
                session.setThinkingEffort(thinkingEffort);
            }
            if (collaborationMode != null) {
                session.setCollaborationMode(collaborationMode);
            }
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateSessionRuntimeBinding(String sessionId,
                                               String mainAgentKey,
                                               String collaborationMode,
                                               String promptVersion,
                                               String promptHash,
                                               String promptSnapshot) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && sessionId != null
                            && sessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            if (session.getMainAgentKey() == null) {
                session.setMainAgentKey(mainAgentKey);
            }
            if (session.getCollaborationMode() == null) {
                session.setCollaborationMode(collaborationMode);
            }
            if (session.getPromptVersion() == null) {
                session.setPromptVersion(promptVersion);
            }
            if (session.getPromptHash() == null) {
                session.setPromptHash(promptHash);
            }
            if (session.getPromptSnapshot() == null) {
                session.setPromptSnapshot(promptSnapshot);
            }
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateSessionRunFinish(String sessionId,
                                          int status,
                                          String latestSummaryText,
                                          int finishedDelta,
                                          int failedDelta,
                                          LocalDateTime lastActiveAt) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && sessionId != null
                            && sessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            session.setStatus(status);
            session.setLatestSummaryText(latestSummaryText);
            session.setFinishedRunCount(defaultCount(session.getFinishedRunCount()) + finishedDelta);
            session.setFailedRunCount(defaultCount(session.getFailedRunCount()) + failedDelta);
            session.setLastActiveAt(lastActiveAt);
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateSessionRunFinishKeepingSummary(String sessionId,
                                                        int status,
                                                        int finishedDelta,
                                                        int failedDelta,
                                                        LocalDateTime lastActiveAt) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && sessionId != null
                            && sessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            session.setStatus(status);
            session.setFinishedRunCount(defaultCount(session.getFinishedRunCount()) + finishedDelta);
            session.setFailedRunCount(defaultCount(session.getFailedRunCount()) + failedDelta);
            session.setLastActiveAt(lastActiveAt);
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        private static int defaultCount(Integer value) {
            return value == null ? 0 : value;
        }

        @Override
        public DialogueSession queryBySessionId(String sessionId) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .findFirst()
                    .map(ExecutionLedgerFixtureFactory::cloneSession)
                    .orElse(null);
        }

        @Override
        public DialogueSession querySessionOwnership(String sessionId) {
            DialogueSession session = queryBySessionId(sessionId);
            if (session == null) {
                return null;
            }
            return DialogueSession.builder()
                    .id(session.getId())
                    .sessionId(session.getSessionId())
                    .userId(session.getUserId())
                    .bindingId(session.getBindingId())
                    .sessionKind(session.getSessionKind())
                    .deleted(session.getDeleted())
                    .build();
        }

        @Override
        public DialogueSessionView querySessionView(String sessionId) {
            DialogueSession session = queryBySessionId(sessionId);
            return session == null ? null : toSessionView(session);
        }

        @Override
        public DialogueSessionView querySessionHistoryView(String sessionId) {
            return querySessionView(sessionId);
        }

        @Override
        public List<DialogueSessionView> queryRecentSessions(int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0)
                    .filter(ExecutionLedgerFixtureFactory::isListableSession)
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(session -> toSessionViewWithSubCount(session, store))
                    .toList();
        }

        @Override
        public DialogueSessionView querySessionViewByUserId(String userId, String sessionId) {
            DialogueSession session = queryBySessionId(sessionId);
            if (session == null || !equalsNullable(userId, session.getUserId())) {
                return null;
            }
            return toSessionView(session);
        }

        @Override
        public List<DialogueSessionView> queryRecentSessionsByUserId(String userId, int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && visibleToUser(userId, item))
                    .filter(ExecutionLedgerFixtureFactory::isListableSession)
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(session -> toSessionViewWithSubCount(session, store))
                    .toList();
        }

        @Override
        public List<DialogueSessionView> queryRecentSessionsByUserIdAfter(String userId,
                                                                          LocalDateTime afterLastActiveAt,
                                                                          Long afterId,
                                                                          int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && visibleToUser(userId, item))
                    .filter(ExecutionLedgerFixtureFactory::isListableSession)
                    .filter(item -> isAfterSessionCursor(item, afterLastActiveAt, afterId))
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(session -> toSessionViewWithSubCount(session, store))
                    .toList();
        }

        @Override
        public List<DialogueSessionView> querySessionsByBindingId(Long bindingId, int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && equalsNullable(bindingId, item.getBindingId()))
                    .filter(ExecutionLedgerFixtureFactory::isListableSession)
                    .sorted(Comparator.comparing(DialogueSession::getLastActiveAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toSessionView)
                    .toList();
        }

        @Override
        public List<DialogueSessionView> querySessionsByRootId(String rootSessionId, int limit) {
            return store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0)
                    .filter(item -> equalsNullable(rootSessionId, item.getRootSessionId()))
                    .sorted(Comparator.comparing(DialogueSession::getCreateTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(DialogueSession::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(limit)
                    .map(ExecutionLedgerFixtureFactory::toSessionView)
                    .toList();
        }

        private boolean isAfterSessionCursor(DialogueSession session,                                             LocalDateTime afterLastActiveAt,
                                             Long afterId) {
            if (afterId == null) {
                return true;
            }
            if (afterLastActiveAt == null) {
                return session.getLastActiveAt() == null && session.getId() < afterId;
            }
            if (session.getLastActiveAt() == null) {
                return true;
            }
            int activityOrder = session.getLastActiveAt().compareTo(afterLastActiveAt);
            return activityOrder < 0 || (activityOrder == 0 && session.getId() < afterId);
        }

        @Override
        public int bumpEventSeq(String sessionId, long eventSeq) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return 0;
            }
            long current = session.getEventSeq() == null ? 0L : session.getEventSeq();
            session.setEventSeq(Math.max(current, eventSeq));
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public int allocateTimelineSeq(String sessionId, int count) {
            DialogueSession session = store.sessions.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getSessionId().equals(sessionId))
                    .findFirst()
                    .orElse(null);
            if (session == null || count <= 0) {
                return 0;
            }
            long current = session.getTimelineSeq() == null ? 0L : session.getTimelineSeq();
            lastInsertId = current + count;
            session.setTimelineSeq(lastInsertId);
            session.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public long queryLastInsertId() {
            return lastInsertId;
        }
    }

    static final class InMemoryLlmInvocationLedgerDao implements ILlmInvocationLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryLlmInvocationLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int insertLlmInvocation(LlmInvocation invocation) {
            for (LlmInvocation existing : store.llmInvocations.values()) {
                if (existing.getDeleted() == 0
                        && existing.getRunId().equals(invocation.getRunId())
                        && existing.getInvocationSeq().equals(invocation.getInvocationSeq())) {
                    throw new IllegalStateException("duplicate llm invocation seq");
                }
            }
            invocation.setId(store.nextLlmId++);
            invocation.setCreateTime(LocalDateTime.now());
            invocation.setUpdateTime(invocation.getCreateTime());
            invocation.setDeleted(0);
            store.llmInvocations.put(invocation.getId(), cloneLlm(invocation));
            return 1;
        }

        @Override
        public int updateLlmInvocationFinish(LlmInvocation invocation) {
            LlmInvocation existing = store.llmInvocations.get(invocation.getId());
            if (existing == null) {
                return 0;
            }
            existing.setStatus(invocation.getStatus());
            existing.setResponseText(invocation.getResponseText());
            existing.setToolCallCount(invocation.getToolCallCount());
            existing.setPromptTokens(invocation.getPromptTokens());
            existing.setCompletionTokens(invocation.getCompletionTokens());
            existing.setTotalTokens(invocation.getTotalTokens());
            existing.setCachedPromptTokens(invocation.getCachedPromptTokens());
            existing.setPromptTextTokens(invocation.getPromptTextTokens());
            existing.setPromptAudioTokens(invocation.getPromptAudioTokens());
            existing.setPromptImageTokens(invocation.getPromptImageTokens());
            existing.setCompletionTextTokens(invocation.getCompletionTextTokens());
            existing.setCompletionAudioTokens(invocation.getCompletionAudioTokens());
            existing.setReasoningTokens(invocation.getReasoningTokens());
            existing.setCacheStatus(invocation.getCacheStatus());
            existing.setCacheRiskFlags(invocation.getCacheRiskFlags());
            existing.setFinishReason(invocation.getFinishReason());
            existing.setErrorMsg(invocation.getErrorMsg());
            existing.setFirstTokenAt(invocation.getFirstTokenAt());
            existing.setFinishedAt(invocation.getFinishedAt());
            existing.setDurationMs(duration(existing.getStartedAt(), invocation.getFinishedAt()));
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public LlmInvocation queryById(Long id) {
            LlmInvocation invocation = store.llmInvocations.get(id);
            return invocation == null || invocation.getDeleted() != 0
                    ? null
                    : cloneLlm(invocation);
        }

        @Override
        public List<LlmInvocation> queryByRunId(Long runId) {
            store.queryLlmByRunIdCount++;
            return store.llmInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(LlmInvocation::getInvocationSeq).thenComparing(LlmInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneLlm)
                    .toList();
        }

        @Override
        public List<LlmInvocation> queryByRunIds(List<Long> runIds) {
            store.queryLlmByRunIdsCount++;
            return store.llmInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(LlmInvocation::getRunId)
                            .thenComparing(LlmInvocation::getInvocationSeq)
                            .thenComparing(LlmInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneLlm)
                    .toList();
        }

        @Override
        public List<LlmUsageSummary> queryUsageSummariesByRunIds(List<Long> runIds) {
            Map<Long, LlmInvocation> latestByRunId = new LinkedHashMap<>();
            for (LlmInvocation invocation : store.llmInvocations.values()) {
                if (invocation.getDeleted() == 0 && runIds.contains(invocation.getRunId())) {
                    latestByRunId.merge(invocation.getRunId(), invocation,
                            (current, candidate) -> candidate.getId() > current.getId() ? candidate : current);
                }
            }
            return latestByRunId.values().stream()
                    .map(invocation -> LlmUsageSummary.builder()
                            .runId(invocation.getRunId())
                            .modelName(invocation.getModelName())
                            .promptTokens(invocation.getPromptTokens())
                            .completionTokens(invocation.getCompletionTokens())
                            .estTotalTokens(invocation.getEstTotalTokens())
                            .estSystemTokens(invocation.getEstSystemTokens())
                            .estMessageTokens(invocation.getEstMessageTokens())
                            .estToolTokens(invocation.getEstToolTokens())
                            .build())
                    .toList();
        }
    }

    static final class InMemoryToolInvocationLedgerDao implements IToolInvocationLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryToolInvocationLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int insertToolInvocation(ToolInvocation invocation) {
            for (ToolInvocation existing : store.toolInvocations.values()) {
                if (existing.getDeleted() != 0) {
                    continue;
                }
                if (existing.getRunId().equals(invocation.getRunId())
                        && existing.getToolCallId().equals(invocation.getToolCallId())) {
                    throw new IllegalStateException("duplicate run/toolCallId");
                }
                if (existing.getLlmInvocationId().equals(invocation.getLlmInvocationId())
                        && existing.getDispatchIndex().equals(invocation.getDispatchIndex())) {
                    throw new IllegalStateException("duplicate llm/dispatchIndex");
                }
            }
            invocation.setId(store.nextToolId++);
            invocation.setCreateTime(LocalDateTime.now());
            invocation.setUpdateTime(invocation.getCreateTime());
            invocation.setDeleted(0);
            store.toolInvocations.put(invocation.getId(), cloneTool(invocation));
            return 1;
        }

        @Override
        public int batchInsertToolInvocations(List<ToolInvocation> invocations) {
            if (invocations == null || invocations.isEmpty()) {
                return 0;
            }
            // 与生产单条多值 INSERT 保持一致：整批判重，任一冲突则整批不写。
            List<ToolInvocation> accepted = new ArrayList<>(invocations.size());
            for (ToolInvocation invocation : invocations) {
                for (ToolInvocation existing : store.toolInvocations.values()) {
                    if (existing.getDeleted() == 0 && isDuplicateInvocation(existing, invocation)) {
                        throw new IllegalStateException("duplicate tool invocation");
                    }
                }
                for (ToolInvocation existing : accepted) {
                    if (isDuplicateInvocation(existing, invocation)) {
                        throw new IllegalStateException("duplicate tool invocation in batch");
                    }
                }
                accepted.add(invocation);
            }
            for (ToolInvocation invocation : accepted) {
                invocation.setId(store.nextToolId++);
                invocation.setCreateTime(LocalDateTime.now());
                invocation.setUpdateTime(invocation.getCreateTime());
                invocation.setDeleted(0);
                store.toolInvocations.put(invocation.getId(), cloneTool(invocation));
            }
            return accepted.size();
        }

        private boolean isDuplicateInvocation(ToolInvocation left, ToolInvocation right) {
            return (left.getRunId().equals(right.getRunId())
                    && left.getToolCallId().equals(right.getToolCallId()))
                    || (left.getLlmInvocationId().equals(right.getLlmInvocationId())
                    && left.getDispatchIndex().equals(right.getDispatchIndex()));
        }

        @Override
        public int updateToolInvocationFinish(ToolInvocation invocation) {
            ToolInvocation existing = store.toolInvocations.get(invocation.getId());
            if (existing == null) {
                return 0;
            }
            existing.setStatus(invocation.getStatus());
            existing.setLlmObservation(invocation.getLlmObservation());
            existing.setToolResult(invocation.getToolResult());
            existing.setErrorMsg(invocation.getErrorMsg());
            existing.setFinishedAt(invocation.getFinishedAt());
            existing.setDurationMs(duration(existing.getStartedAt(), invocation.getFinishedAt()));
            existing.setUpdateTime(LocalDateTime.now());
            return 1;
        }

        @Override
        public ToolInvocation queryById(Long id) {
            ToolInvocation invocation = store.toolInvocations.get(id);
            return invocation == null || invocation.getDeleted() != 0
                    ? null
                    : cloneTool(invocation);
        }

        @Override
        public ToolInvocation queryByRunIdAndToolCallId(Long runId, String toolCallId) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && java.util.Objects.equals(item.getRunId(), runId)
                            && java.util.Objects.equals(item.getToolCallId(), toolCallId))
                    .findFirst()
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .orElse(null);
        }

        @Override
        public List<ToolInvocation> queryOrphanedRunningToolInvocations(LocalDateTime startedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<ToolInvocation> queryByRunId(Long runId) {
            store.queryToolByRunIdCount++;
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocation> queryByRunIds(List<Long> runIds) {
            store.queryToolByRunIdsCount++;
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(ToolInvocation::getRunId)
                            .thenComparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocation> queryByLlmInvocationIds(List<Long> llmInvocationIds) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && llmInvocationIds.contains(item.getLlmInvocationId()))
                    .sorted(Comparator.comparing(ToolInvocation::getLlmInvocationId)
                            .thenComparing(ToolInvocation::getDispatchIndex)
                            .thenComparing(ToolInvocation::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneTool)
                    .toList();
        }

        @Override
        public List<ToolInvocationView> queryRecentByToolName(String userId, String toolName, int limit) {
            return store.toolInvocations.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getToolName().equals(toolName))
                    .filter(item -> {
                        DialogueRun run = store.runs.get(item.getRunId());
                        return run != null && userId != null && userId.equals(run.getUserId());
                    })
                    .sorted(Comparator.comparing(ToolInvocation::getCreateTime, Comparator.reverseOrder())
                            .thenComparing(ToolInvocation::getId, Comparator.reverseOrder()))
                    .limit(limit)
                    .map(item -> {
                        DialogueRun run = store.runs.get(item.getRunId());
                        int artifactCount = (int) store.artifacts.values().stream()
                                .filter(artifact -> artifact.getDeleted() == 0 && item.getId().equals(artifact.getToolInvocationId()))
                                .count();
                        return ToolInvocationView.builder()
                                .id(item.getId())
                                .runId(item.getRunId())
                                .llmInvocationId(item.getLlmInvocationId())
                                .requestId(run == null ? null : run.getRequestId())
                                .sessionId(run == null ? null : run.getSessionId())
                                .toolCallId(item.getToolCallId())
                                .dispatchIndex(item.getDispatchIndex())
                                .agentName(item.getAgentName())
                                .stepNo(item.getStepNo())
                                .toolName(item.getToolName())
                                .toolProvider(item.getToolProvider())
                                .inputJson(item.getInputJson())
                                .llmObservation(item.getLlmObservation())
                                .toolResult(item.getToolResult())
                                .status(item.getStatus())
                                .errorMsg(item.getErrorMsg())
                                .durationMs(item.getDurationMs())
                                .artifactCount(artifactCount)
                                .startedAt(item.getStartedAt())
                                .finishedAt(item.getFinishedAt())
                                .createTime(item.getCreateTime())
                                .build();
                    })
                    .toList();
        }
    }

    static final class InMemoryArtifactLedgerDao implements IArtifactLedgerDao {
        private final InMemoryLedgerStore store;

        private InMemoryArtifactLedgerDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public int batchInsertArtifacts(List<ArtifactRecord> records) {
            int inserted = 0;
            for (ArtifactRecord record : records) {
                boolean exists = store.artifacts.values().stream()
                        .anyMatch(existing -> existing.getDeleted() == 0
                                && equalsNullable(existing.getRequestId(), record.getRequestId())
                                && equalsNullable(existing.getToolCallId(), record.getToolCallId())
                                && equalsNullable(existing.getStorageKey(), record.getStorageKey()));
                if (exists) {
                    continue;
                }
                record.setId(store.nextArtifactId++);
                record.setCreateTime(LocalDateTime.now());
                record.setUpdateTime(record.getCreateTime());
                record.setDeleted(0);
                store.artifacts.put(record.getId(), cloneArtifact(record));
                inserted++;
            }
            return inserted;
        }

        @Override
        public List<ArtifactRecord> queryByRunId(Long runId) {
            store.queryArtifactsByRunIdCount++;
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0 && item.getRunId().equals(runId))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryByRunIdWithSubagentFold(Long runId) {
            store.queryArtifactsByRunIdCount++;
            return foldArtifactsByRunIds(List.of(runId));
        }

        @Override
        public List<ArtifactRecord> queryByRunIds(List<Long> runIds) {
            store.queryArtifactsByRunIdsCount++;
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0 && runIds.contains(item.getRunId()))
                    .sorted(Comparator.comparing(ArtifactRecord::getRunId).reversed()
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryByRunIdsWithSubagentFold(List<Long> runIds) {
            store.queryArtifactsByRunIdsCount++;
            return foldArtifactsByRunIds(runIds);
        }

        private List<ArtifactRecord> foldArtifactsByRunIds(List<Long> runIds) {
            if (runIds == null || runIds.isEmpty()) {
                return List.of();
            }
            List<ArtifactRecord> folded = new ArrayList<>();
            for (ArtifactRecord artifact : store.artifacts.values()) {
                if (artifact.getDeleted() != 0
                        || !ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(artifact.getVisibility())) {
                    continue;
                }
                if (runIds.contains(artifact.getRunId())) {
                    folded.add(cloneArtifact(artifact));
                    continue;
                }
                if (!ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(artifact.getArtifactRole())
                        || artifact.getToolInvocationId() == null) {
                    continue;
                }
                ToolInvocation child = store.toolInvocations.get(artifact.getToolInvocationId());
                if (child == null || child.getDeleted() != 0 || child.getSubAgentId() == null) {
                    continue;
                }
                ToolInvocation parent = store.toolInvocations.values().stream()
                        .filter(candidate -> candidate.getDeleted() == 0
                                && equalsNullable(candidate.getToolCallId(), child.getParentToolCallId())
                                && runIds.contains(candidate.getRunId()))
                        .findFirst()
                        .orElse(null);
                if (parent == null) {
                    continue;
                }
                DialogueRun parentRun = store.runs.get(parent.getRunId());
                folded.add(foldArtifact(artifact, parent, parentRun));
            }
            return folded.stream()
                    .sorted(Comparator.comparing(ArtifactRecord::getRunId)
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryBySessionIdWithLimit(String sessionId, int limit) {
            List<ArtifactRecord> all = queryBySessionId(sessionId);
            if (limit <= 0 || all.size() <= limit) {
                return all;
            }
            return new ArrayList<>(all.subList(0, limit));
        }

        @Override
        public List<ArtifactRecord> queryBySessionId(String sessionId) {
            store.queryArtifactsBySessionIdCount++;
            Map<Long, ArtifactRecord> visible = new LinkedHashMap<>();
            for (ArtifactRecord artifact : store.artifacts.values()) {
                if (artifact.getDeleted() != 0
                        || !ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(artifact.getVisibility())) {
                    continue;
                }
                DialogueRun artifactRun = store.runs.get(artifact.getRunId());
                if (artifactRun != null && sessionId.equals(artifactRun.getSessionId())) {
                    visible.put(artifact.getId(), cloneArtifact(artifact));
                    continue;
                }

                ToolInvocation child = artifact.getToolInvocationId() == null
                        ? null
                        : store.toolInvocations.get(artifact.getToolInvocationId());
                if (child != null && child.getDeleted() == 0 && child.getSubAgentId() != null) {
                    boolean attachedToParentSession = store.toolInvocations.values().stream()
                            .anyMatch(parent -> parent.getDeleted() == 0
                                    && equalsNullable(parent.getToolCallId(), child.getParentToolCallId())
                                    && store.runs.containsKey(parent.getRunId())
                                    && sessionId.equals(store.runs.get(parent.getRunId()).getSessionId()));
                    if (attachedToParentSession) {
                        visible.put(artifact.getId(), cloneArtifact(artifact));
                        continue;
                    }
                }

                if (artifactRun != null) {
                    DialogueSession childSession = store.sessions.values().stream()
                            .filter(session -> session.getDeleted() == 0
                                    && artifactRun.getSessionId().equals(session.getSessionId())
                                    && sessionId.equals(session.getParentSessionId()))
                            .findFirst()
                            .orElse(null);
                    if (childSession != null) {
                        visible.put(artifact.getId(), cloneArtifact(artifact));
                    }
                }
            }
            return visible.values().stream()
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime)
                    .thenComparing(ArtifactRecord::getId))
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryBySessionIdLimit(String sessionId, int limit) {
            return queryBySessionId(sessionId).stream().limit(limit).toList();
        }

        private ArtifactRecord foldArtifact(ArtifactRecord artifact,
                                            ToolInvocation parent,
                                            DialogueRun parentRun) {
            ArtifactRecord folded = cloneArtifact(artifact);
            folded.setRunId(parent.getRunId());
            folded.setRequestId(parentRun == null ? null : parentRun.getRequestId());
            folded.setToolInvocationId(parent.getId());
            return folded;
        }

        @Override
        public List<ArtifactRecord> queryByToolInvocationIds(List<Long> toolInvocationIds) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && item.getToolInvocationId() != null
                            && toolInvocationIds.contains(item.getToolInvocationId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getToolInvocationId)
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryInputArtifactsByRunIds(List<Long> runIds) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && runIds.contains(item.getRunId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_INPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getRunId)
                            .thenComparing(ArtifactRecord::getCreateTime)
                            .thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByToolInvocationId(Long toolInvocationId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && toolInvocationId != null
                            && toolInvocationId.equals(item.getToolInvocationId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByRunIdAndToolCallId(Long runId, String toolCallId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && runId != null
                            && runId.equals(item.getRunId())
                            && equalsNullable(toolCallId, item.getToolCallId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }

        @Override
        public List<ArtifactRecord> queryOutputArtifactsByRequestIdAndToolCallId(String requestId, String toolCallId) {
            return store.artifacts.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && equalsNullable(requestId, item.getRequestId())
                            && equalsNullable(toolCallId, item.getToolCallId())
                            && ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(item.getArtifactRole())
                            && ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(item.getVisibility()))
                    .sorted(Comparator.comparing(ArtifactRecord::getCreateTime).thenComparing(ArtifactRecord::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneArtifact)
                    .toList();
        }
    }

    static final class InMemoryWorkingMemoryCompactionReadDao
            implements IWorkingMemoryCompactionReadDao {
        private final InMemoryLedgerStore store;

        private InMemoryWorkingMemoryCompactionReadDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public List<WorkingMemoryCompactionEvent> queryByTriggerRequestIds(
                List<String> triggerRequestIds) {
            store.queryCompactionByRequestIdsCount++;
            if (triggerRequestIds == null || triggerRequestIds.isEmpty()) {
                return List.of();
            }
            return store.compactionEvents.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && triggerRequestIds.contains(item.getTriggerRequestId()))
                    .sorted(Comparator.comparing(WorkingMemoryCompactionEvent::getTriggerRequestId)
                            .thenComparing(WorkingMemoryCompactionEvent::getCreateTime)
                            .thenComparing(WorkingMemoryCompactionEvent::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneCompaction)
                    .toList();
        }
    }

    static final class InMemoryLtmForkExecutionReadDao
            implements ILtmForkExecutionReadDao {
        private final InMemoryLedgerStore store;

        private InMemoryLtmForkExecutionReadDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public List<LtmForkExecutionEvent> queryByTriggerRequestIds(
                List<String> triggerRequestIds) {
            store.queryMemoryForkByRequestIdsCount++;
            if (triggerRequestIds == null || triggerRequestIds.isEmpty()) {
                return List.of();
            }
            return store.ltmForkEvents.values().stream()
                    .filter(item -> item.getDeleted() == 0
                            && triggerRequestIds.contains(item.getTriggerRequestId()))
                    .sorted(Comparator.comparing(LtmForkExecutionEvent::getTriggerRequestId)
                            .thenComparing(LtmForkExecutionEvent::getCreateTime)
                            .thenComparing(LtmForkExecutionEvent::getId))
                    .map(ExecutionLedgerFixtureFactory::cloneLtmFork)
                    .toList();
        }
    }

    static final class InMemoryWorkingMemoryConversationReadDao
            implements IWorkingMemoryConversationReadDao {
        private final InMemoryLedgerStore store;

        private InMemoryWorkingMemoryConversationReadDao(InMemoryLedgerStore store) {
            this.store = store;
        }

        @Override
        public List<WorkingMemoryConversationRow> queryBySessionIds(
                List<String> sessionIds) {
            store.queryConversationBySessionIdsCount++;
            if (store.failConversationRead) {
                throw new IllegalStateException("conversation read failed");
            }
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }
            return store.conversationRows.stream()
                    .filter(row -> sessionIds.contains(row.getSessionId()))
                    .filter(row -> !"MODEL_ONLY".equals(row.getVisibility()))
                    .sorted(Comparator.comparing(WorkingMemoryConversationRow::getSessionId)
                            .thenComparing(WorkingMemoryConversationRow::getTurnSeq)
                            .thenComparing(WorkingMemoryConversationRow::getSeqNo)
                            .thenComparing(WorkingMemoryConversationRow::getMessageId))
                    .map(ExecutionLedgerFixtureFactory::cloneConversationRow)
                    .toList();
        }
    }

    private static boolean equalsNullable(Object left, Object right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static long duration(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return 0L;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    private static DialogueRun cloneRun(DialogueRun run) {
        return DialogueRun.builder()
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
                .updateTime(run.getUpdateTime())
                .deleted(run.getDeleted())
                .build();
    }

    private static DialogueSession cloneSession(DialogueSession session) {
        return DialogueSession.builder()
                .id(session.getId())
                .sessionId(session.getSessionId())
                .userId(session.getUserId())
                .bindingId(session.getBindingId())
                .channelChatType(session.getChannelChatType())
                .channelTargetId(session.getChannelTargetId())
                .channelLastMsgId(session.getChannelLastMsgId())
                .channelLastReceivedAt(session.getChannelLastReceivedAt())
                .sessionKind(session.getSessionKind())
                .parentSessionId(session.getParentSessionId())
                .rootSessionId(session.getRootSessionId())
                .subAgentType(session.getSubAgentType())
                .mainAgentKey(session.getMainAgentKey())
                .promptVersion(session.getPromptVersion())
                .promptHash(session.getPromptHash())
                .title(session.getTitle())
                .status(session.getStatus())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryText(session.getLatestQueryText())
                .latestSummaryText(session.getLatestSummaryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .collaborationMode(session.getCollaborationMode())
                .eventSeq(session.getEventSeq())
                .createTime(session.getCreateTime())
                .updateTime(session.getUpdateTime())
                .deleted(session.getDeleted())
                .build();
    }

    private static LlmInvocation cloneLlm(LlmInvocation invocation) {
        return LlmInvocation.builder()
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
                .cachedPromptTokens(invocation.getCachedPromptTokens())
                .promptTextTokens(invocation.getPromptTextTokens())
                .promptAudioTokens(invocation.getPromptAudioTokens())
                .promptImageTokens(invocation.getPromptImageTokens())
                .completionTextTokens(invocation.getCompletionTextTokens())
                .completionAudioTokens(invocation.getCompletionAudioTokens())
                .reasoningTokens(invocation.getReasoningTokens())
                .systemFingerprint(invocation.getSystemFingerprint())
                .estTotalTokens(invocation.getEstTotalTokens())
                .estSystemTokens(invocation.getEstSystemTokens())
                .estMessageTokens(invocation.getEstMessageTokens())
                .estToolTokens(invocation.getEstToolTokens())
                .messageCount(invocation.getMessageCount())
                .toolCount(invocation.getToolCount())
                .cacheStatus(invocation.getCacheStatus())
                .cacheRiskFlags(invocation.getCacheRiskFlags())
                .finishReason(invocation.getFinishReason())
                .status(invocation.getStatus())
                .errorMsg(invocation.getErrorMsg())
                .startedAt(invocation.getStartedAt())
                .firstTokenAt(invocation.getFirstTokenAt())
                .finishedAt(invocation.getFinishedAt())
                .durationMs(invocation.getDurationMs())
                .createTime(invocation.getCreateTime())
                .updateTime(invocation.getUpdateTime())
                .deleted(invocation.getDeleted())
                .build();
    }

    private static ToolInvocation cloneTool(ToolInvocation invocation) {
        return ToolInvocation.builder()
                .id(invocation.getId())
                .runId(invocation.getRunId())
                .llmInvocationId(invocation.getLlmInvocationId())
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
                .startedAt(invocation.getStartedAt())
                .finishedAt(invocation.getFinishedAt())
                .durationMs(invocation.getDurationMs())
                .createTime(invocation.getCreateTime())
                .updateTime(invocation.getUpdateTime())
                .deleted(invocation.getDeleted())
                .build();
    }

    private static ArtifactRecord cloneArtifact(ArtifactRecord artifact) {
        return ArtifactRecord.builder()
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
                .updateTime(artifact.getUpdateTime())
                .deleted(artifact.getDeleted())
                .build();
    }

    private static WorkingMemoryCompactionEvent cloneCompaction(
            WorkingMemoryCompactionEvent event) {
        return WorkingMemoryCompactionEvent.builder()
                .id(event.getId())
                .sessionId(event.getSessionId())
                .triggerRequestId(event.getTriggerRequestId())
                .compactRequestId(event.getCompactRequestId())
                .strategy(event.getStrategy())
                .status(event.getStatus())
                .beforeTokens(event.getBeforeTokens())
                .afterTokens(event.getAfterTokens())
                .beforeMessageCount(event.getBeforeMessageCount())
                .afterMessageCount(event.getAfterMessageCount())
                .thresholdTokens(event.getThresholdTokens())
                .summaryText(event.getSummaryText())
                .beforeMessagesJson(event.getBeforeMessagesJson())
                .afterMessagesJson(event.getAfterMessagesJson())
                .errorMessage(event.getErrorMessage())
                .createTime(event.getCreateTime())
                .updateTime(event.getUpdateTime())
                .deleted(event.getDeleted())
                .build();
    }

    private static LtmForkExecutionEvent cloneLtmFork(LtmForkExecutionEvent event) {
        return LtmForkExecutionEvent.builder()
                .id(event.getId())
                .sessionId(event.getSessionId())
                .triggerRequestId(event.getTriggerRequestId())
                .forkRequestId(event.getForkRequestId())
                .forkKind(event.getForkKind())
                .status(event.getStatus())
                .skipReason(event.getSkipReason())
                .ownerType(event.getOwnerType())
                .ownerId(event.getOwnerId())
                .userTurns(event.getUserTurns())
                .snapshotMessageCount(event.getSnapshotMessageCount())
                .maxSteps(event.getMaxSteps())
                .timeoutSeconds(event.getTimeoutSeconds())
                .durationMs(event.getDurationMs())
                .entriesBefore(event.getEntriesBefore())
                .entriesAfter(event.getEntriesAfter())
                .appliedCount(event.getAppliedCount())
                .errorMessage(event.getErrorMessage())
                .detailJson(event.getDetailJson())
                .createTime(event.getCreateTime())
                .updateTime(event.getUpdateTime())
                .deleted(event.getDeleted())
                .build();
    }

    private static WorkingMemoryConversationRow cloneConversationRow(
            WorkingMemoryConversationRow row) {
        return WorkingMemoryConversationRow.builder()
                .turnId(row.getTurnId())
                .messageId(row.getMessageId())
                .sessionId(row.getSessionId())
                .requestId(row.getRequestId())
                .runId(row.getRunId())
                .turnSeq(row.getTurnSeq())
                .seqNo(row.getSeqNo())
                .role(row.getRole())
                .content(row.getContent())
                .toolCallId(row.getToolCallId())
                .toolCallsJson(row.getToolCallsJson())
                .messageKind(row.getMessageKind())
                .visibility(row.getVisibility())
                .tokenEstimate(row.getTokenEstimate())
                .createTime(row.getCreateTime())
                .build();
    }

    private static ToolOutputView cloneOutputView(ToolOutputView view) {
        if (view == null) {
            return null;
        }
        return ToolOutputView.builder()
                .toolName(view.getToolName())
                .requestId(view.getRequestId())
                .requestSource(view.getRequestSource())
                .sessionId(view.getSessionId())
                .toolCallId(view.getToolCallId())
                .status(view.getStatus())
                .errorMsg(view.getErrorMsg())
                .createdAt(view.getCreatedAt())
                .structuredOutput(view.getStructuredOutput())
                .build();
    }

    private static String buildDirectKey(String requestId, String toolCallId) {
        return String.valueOf(requestId) + "||" + String.valueOf(toolCallId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultSessionKind(String kind) {
        return isBlank(kind) ? "conversation" : kind;
    }

    /**
     * 与生产 SQL 的 {@code session_kind = 'conversation'} 白名单对齐：只有用户会话进侧栏列表。
     */
    private static boolean isListableSession(DialogueSession session) {
        return session != null && SessionKind.isListableCode(session.getSessionKind());
    }

    private static DialogueRunView toRunView(DialogueRun run) {
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

    /** 侧栏列表视图：额外带上子会话数量，与生产 SQL 的 sub_session_count 语义保持一致。 */
    private static DialogueSessionView toSessionViewWithSubCount(DialogueSession session,
                                                                InMemoryLedgerStore store) {
        DialogueSessionView view = toSessionView(session);
        int subSessionCount = (int) store.sessions.values().stream()
                .filter(item -> item.getDeleted() == 0)
                .filter(item -> session.getSessionId().equals(item.getRootSessionId()))
                .filter(item -> !session.getSessionId().equals(item.getSessionId()))
                .count();
        view.setSubSessionCount(subSessionCount);
        return view;
    }

    private static DialogueSessionView toSessionView(DialogueSession session) {
        return DialogueSessionView.builder()
                .id(session.getId())
                .sessionId(session.getSessionId())
                .userId(session.getUserId())
                .bindingId(session.getBindingId())
                .sessionKind(session.getSessionKind())
                .parentSessionId(session.getParentSessionId())
                .rootSessionId(session.getRootSessionId())
                .subAgentType(session.getSubAgentType())
                .mainAgentKey(session.getMainAgentKey())
                .promptVersion(session.getPromptVersion())
                .promptHash(session.getPromptHash())
                .title(session.getTitle())
                .status(session.getStatus())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryText(session.getLatestQueryText())
                .latestSummaryText(session.getLatestSummaryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .collaborationMode(session.getCollaborationMode())
                .createTime(session.getCreateTime())
                .build();
    }
}
