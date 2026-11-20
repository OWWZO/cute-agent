package org.wwz.ai.domain.agent.ledger.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationSnapshotRecord;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputNames;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputWriter;
import org.wwz.ai.domain.agent.ledger.timeline.ArtifactTimelineFact;
import org.wwz.ai.domain.agent.ledger.timeline.LlmTimelineFact;
import org.wwz.ai.domain.agent.ledger.timeline.RunTimelineFact;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemKey;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemPhase;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemStatus;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemWriteException;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineProjectionWriter;
import org.wwz.ai.domain.agent.ledger.timeline.ToolTimelineFact;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 执行账本写入服务。
 * 统一承担 fail-open、日志与内存指标累计，避免调用点到处散落 try/catch。
 */
@Slf4j
@Service
public class AgentExecutionRecorderImpl implements AgentExecutionRecorder {

    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;
    private final ToolOutputWriter toolOutputWriter;
    private final TimelineProjectionWriter timelineProjectionWriter;

    private final Map<String, LongAdder> successCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failureCounters = new ConcurrentHashMap<>();

    public AgentExecutionRecorderImpl(IExecutionLedgerWriteRepository executionLedgerWriteRepository,
                                      ToolOutputWriter toolOutputWriter) {
        this(executionLedgerWriteRepository, toolOutputWriter, (TimelineProjectionWriter) null);
    }

    public AgentExecutionRecorderImpl(IExecutionLedgerWriteRepository executionLedgerWriteRepository,
                                      ToolOutputWriter toolOutputWriter,
                                      TimelineProjectionWriter timelineProjectionWriter) {
        this.executionLedgerWriteRepository = executionLedgerWriteRepository;
        this.toolOutputWriter = toolOutputWriter;
        this.timelineProjectionWriter = timelineProjectionWriter;
    }

    @Autowired
    public AgentExecutionRecorderImpl(IExecutionLedgerWriteRepository executionLedgerWriteRepository,
                                      ToolOutputWriter toolOutputWriter,
                                      ObjectProvider<TimelineProjectionWriter> timelineProjectionWriterProvider) {
        this.executionLedgerWriteRepository = executionLedgerWriteRepository;
        this.toolOutputWriter = toolOutputWriter;
        this.timelineProjectionWriter = timelineProjectionWriterProvider == null
                ? null
                : timelineProjectionWriterProvider.getIfAvailable();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRun(DialogueRunStartRecord record) {
        // sessionId 是会话汇总行的主键：为空时写会话会生成 key=null 的脏行，直接拒绝初始化。
        if (record == null
                || StringUtils.isBlank(record.getRequestId())
                || StringUtils.isBlank(record.getSessionId())) {
            return null;
        }
        LocalDateTime startedAt = defaultNow(record.getStartedAt());
        SessionHeadValues sessionHead;
        try {
            // 先完成只读的会话头解析再落 run：读失败时不应留下永久 RUNNING 的孤儿 run。
            sessionHead = resolveSessionHead(record.getSessionId(), record.getQueryText());
        } catch (Exception e) {
            markFailure("createRun", record.getRequestId(), null, null, e);
            return null;
        }
        DialogueRun entity = DialogueRun.builder()
                .runUid(StringUtils.defaultIfBlank(record.getRunUid(), record.getRequestId()))
                .requestId(record.getRequestId())
                .sessionId(record.getSessionId())
                .userId(record.getUserId())
                .entryAgent(record.getEntryAgent())
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .queryText(record.getQueryText())
                .llmCallCount(0)
                .toolCallCount(0)
                .artifactCount(0)
                .promptTokensTotal(0)
                .completionTokensTotal(0)
                .totalTokensTotal(0)
                .startedAt(startedAt)
                .build();
        try {
            // run 是整条执行账本的根事实，必须先落库，后续 LLM、tool 和 artifact 才能挂载到 runId。
            executionLedgerWriteRepository.insertRun(entity);
            // 会话主表由数据库原子累加 run_count：并发 run 不会互相覆盖，started_at 保留首次值。
            executionLedgerWriteRepository.upsertSessionOnRunStart(DialogueSessionUpsertRecord.builder()
                    .sessionId(record.getSessionId())
                    .userId(record.getUserId())
                    // 兜底默认「自己即根」：已有子会话行时 ON DUPLICATE 段不覆盖 root_session_id，
                    // 由 SubAgentSessionPortAdapter 建行时写入的树根值保持不变。
                    .rootSessionId(record.getSessionId())
                    .title(sessionHead.title())
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .latestRequestId(record.getRequestId())
                    .latestQueryText(sessionHead.latestQueryText())
                    .latestSummaryText(null)
                    .startedAt(startedAt)
                    .lastActiveAt(startedAt)
                    .build());
            markSuccess("createRun");
            return entity.getId();
        } catch (Exception e) {
            markFailure("createRun", record.getRequestId(), entity.getId(), null, e);
            // run 行可能已落库：调用方拿到 null 会当作「账本未启用」而永不结算，
            // 因此这里必须补偿成失败终态，避免留下永久 RUNNING 的孤儿 run。
            compensateFailedRunCreate(entity, e);
            return null;
        }
    }

    private void compensateFailedRunCreate(DialogueRun run, Exception cause) {
        if (run == null || run.getId() == null) {
            return;
        }
        try {
            run.setStatus(ExecutionLedgerConstants.STATUS_FAILED);
            run.setErrorCode("LEDGER_INIT_FAILED");
            run.setErrorMsg(trimText("ledger 初始化失败: " + cause.getMessage(), 2000));
            run.setFinishedAt(LocalDateTime.now());
            executionLedgerWriteRepository.updateRunFinish(run);
        } catch (Exception compensationError) {
            log.error("createRun compensation failed, runId={}", run.getId(), compensationError);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishRun(DialogueRunFinishRecord record) {
        finishRunInternal(record, true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void finishRunWithoutTimeline(DialogueRunFinishRecord record) {
        finishRunInternal(record, false);
    }

    private void finishRunInternal(DialogueRunFinishRecord record, boolean writeTimeline) {
        // status 会在写会话计数时拆箱成 int：null 若留到 updateRunFinish 之后才炸，
        // run 已进入终态、计数却永久丢失，因此必须在任何写入前拒绝。
        if (record == null || record.getRunId() == null || StringUtils.isBlank(record.getRequestId())
                || record.getStatus() == null) {
            return;
        }
        try {
            // run 结束时重新读取所有子事实再汇总计数，避免并发写入期间依赖创建阶段的过期快照。
            DialogueRun existing = executionLedgerWriteRepository.queryRunByRequestId(record.getRequestId());
            if (existing == null) {
                log.warn("finishRun skipped: run not found, requestId={}, runId={}",
                        record.getRequestId(), record.getRunId());
                return;
            }
            List<LlmInvocation> llmInvocations = executionLedgerWriteRepository.queryLlmInvocationsByRunId(existing.getId());
            List<ToolInvocation> toolInvocations = executionLedgerWriteRepository.queryToolInvocationsByRunId(existing.getId());
            List<ArtifactRecord> artifacts = executionLedgerWriteRepository.queryArtifactsByRunId(existing.getId());
            LocalDateTime finishedAt = defaultNow(record.getFinishedAt());
            // 终态统计从子事实实时汇总，避免并发调用过程中依赖可能过期的计数器。
            DialogueRun updateEntity = DialogueRun.builder()
                    .id(existing.getId())
                    .status(record.getStatus())
                    .finalSummaryText(record.getFinalSummaryText())
                    .llmCallCount(sizeOf(llmInvocations))
                    .toolCallCount(sizeOf(toolInvocations))
                    .artifactCount(sizeOf(artifacts))
                    .promptTokensTotal(sumPromptTokens(llmInvocations))
                    .completionTokensTotal(sumCompletionTokens(llmInvocations))
                    .totalTokensTotal(sumTotalTokens(llmInvocations))
                    .errorCode(record.getErrorCode())
                    .errorMsg(trimText(record.getErrorMsg(), 2000))
                    .finishedAt(finishedAt)
                    .durationMs(calculateDuration(existing.getStartedAt(), finishedAt))
                    .build();
            // 终态守卫：只有 run 仍处于非终态时写入才生效。返回 0 说明已被并发/更早的回调结束，
            // 此时既不能用陈旧状态覆盖 run，也不能重复累加会话计数。
            int updated = executionLedgerWriteRepository.updateRunFinish(updateEntity);
            if (updated == 0) {
                return;
            }
            if (StringUtils.isBlank(existing.getSessionId())) {
                // 没有会话归属就没有可累加的对象；与 BackgroundRunReconciler 保持同一口径。
                markSuccess("finishRun");
                return;
            }
            // 会话计数由数据库原子累加，只在首次进入终态时下发 delta=1。
            executionLedgerWriteRepository.updateSessionRunFinish(
                    existing.getSessionId(),
                    record.getStatus(),
                    record.getFinalSummaryText(),
                    isSuccessStatus(record.getStatus()) ? 1 : 0,
                    isFailedStatus(record.getStatus()) ? 1 : 0,
                    finishedAt);
            if (writeTimeline) {
                recordRunTimelineItem(existing, record);
            }
            markSuccess("finishRun");
        } catch (TimelineItemWriteException e) {
            markFailure("finishRun", record.getRequestId(), record.getRunId(), null, e);
            throw e;
        } catch (Exception e) {
            markFailure("finishRun", record.getRequestId(), record.getRunId(), null, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createLlmInvocation(LlmInvocationStartRecord record) {
        if (record == null || record.getRunId() == null) {
            return null;
        }
        if (StringUtils.isBlank(record.getAgentName())) {
            markFailure("createLlmInvocation", record.getRequestId(), record.getRunId(), null,
                    new IllegalArgumentException("agentName must not be blank"));
            return null;
        }
        LlmInvocation entity = LlmInvocation.builder()
                .runId(record.getRunId())
                .invocationSeq(record.getInvocationSeq())
                .agentName(record.getAgentName())
                .stepNo(record.getStepNo())
                .callKind(record.getCallKind())
                .streaming(Boolean.TRUE.equals(record.getStreaming()) ? 1 : 0)
                .modelName(record.getModelName())
                .toolCallCount(0)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .systemFingerprint(record.getSystemFingerprint())
                .estTotalTokens(record.getEstTotalTokens())
                .estSystemTokens(record.getEstSystemTokens())
                .estMessageTokens(record.getEstMessageTokens())
                .estToolTokens(record.getEstToolTokens())
                .messageCount(record.getMessageCount())
                .toolCount(record.getToolCount())
                .cacheStatus(record.getCacheStatus())
                .cacheRiskFlags(record.getCacheRiskFlags())
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .startedAt(defaultNow(record.getStartedAt()))
                .build();
        try {
            executionLedgerWriteRepository.insertLlmInvocation(entity);
            DialogueRun sourceRun =
                    executionLedgerWriteRepository.queryRunById(record.getRunId());
            String sessionId = StringUtils.defaultIfBlank(
                    record.getSessionId(),
                    sourceRun == null ? null : sourceRun.getSessionId());
            String requestId = StringUtils.defaultIfBlank(
                    record.getRequestId(),
                    sourceRun == null ? null : sourceRun.getRequestId());
            writeLlmTimelineItem(new LlmTimelineFact(
                    new TimelineItemKey.Llm(entity.getId()),
                    sessionId,
                    requestId,
                    StringUtils.defaultIfBlank(record.getAgentId(), record.getAgentName()),
                    TimelineItemPhase.INTERMEDIATE,
                    TimelineItemStatus.RUNNING,
                    null
            ), true);
            markSuccess("createLlmInvocation");
            return entity.getId();
        } catch (TimelineItemWriteException e) {
            markFailure("createLlmInvocation", record.getRequestId(), record.getRunId(), null, e);
            throw e;
        } catch (Exception e) {
            markFailure("createLlmInvocation", record.getRequestId(), record.getRunId(), null, e);
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishLlmInvocation(LlmInvocationFinishRecord record) {
        finishLlmInvocationInternal(record, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishLlmInvocationWithoutTimeline(LlmInvocationFinishRecord record) {
        finishLlmInvocationInternal(record, false);
    }

    private void finishLlmInvocationInternal(LlmInvocationFinishRecord record, boolean writeTimeline) {
        if (record == null || record.getLlmInvocationId() == null) {
            return;
        }
        try {
            LocalDateTime finishedAt = defaultNow(record.getFinishedAt());
            executionLedgerWriteRepository.updateLlmInvocationFinish(LlmInvocation.builder()
                    .id(record.getLlmInvocationId())
                    .status(record.getStatus())
                    .responseText(record.getResponseText())
                    .reasoningContent(record.getReasoningContent())
                    .toolCallCount(defaultZero(record.getToolCallCount()))
                    .promptTokens(defaultZero(record.getPromptTokens()))
                    .completionTokens(defaultZero(record.getCompletionTokens()))
                    .totalTokens(defaultZero(record.getTotalTokens()))
                    .cachedPromptTokens(record.getCachedPromptTokens())
                    .promptTextTokens(record.getPromptTextTokens())
                    .promptAudioTokens(record.getPromptAudioTokens())
                    .promptImageTokens(record.getPromptImageTokens())
                    .completionTextTokens(record.getCompletionTextTokens())
                    .completionAudioTokens(record.getCompletionAudioTokens())
                    .reasoningTokens(record.getReasoningTokens())
                    .cacheStatus(record.getCacheStatus())
                    .cacheRiskFlags(record.getCacheRiskFlags())
                    .finishReason(record.getFinishReason())
                    .errorMsg(trimText(record.getErrorMsg(), 2000))
                    .firstTokenAt(record.getFirstTokenAt())
                    .finishedAt(finishedAt)
                    .build());
            if (writeTimeline) {
                recordLlmFinishTimelineItem(record);
            }
            markSuccess("finishLlmInvocation");
        } catch (TimelineItemWriteException e) {
            markFailure("finishLlmInvocation", record.getRequestId(), null, null, e);
            throw e;
        } catch (Exception e) {
            markFailure("finishLlmInvocation", record.getRequestId(), null, null, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> createToolInvocations(ToolInvocationBatchStartRecord record) {
        return createToolInvocationsInternal(record, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> createToolInvocationsWithoutTimeline(ToolInvocationBatchStartRecord record) {
        return createToolInvocationsInternal(record, false);
    }

    private Map<String, Long> createToolInvocationsInternal(ToolInvocationBatchStartRecord record,
                                                            boolean writeTimeline) {
        Map<String, Long> mapping = new LinkedHashMap<>();
        if (record == null || record.getRunId() == null || CollectionUtils.isEmpty(record.getItems())) {
            return mapping;
        }
        List<ToolInvocation> entities = new ArrayList<>(record.getItems().size());
        List<String> toolCallIds = new ArrayList<>(record.getItems().size());
        for (ToolInvocationBatchStartRecord.Item item : record.getItems()) {
            if (item == null || StringUtils.isBlank(item.getToolCallId())) {
                continue;
            }
            entities.add(ToolInvocation.builder()
                    .runId(record.getRunId())
                    .llmInvocationId(record.getLlmInvocationId())
                    .toolCallId(item.getToolCallId())
                    .parentToolCallId(item.getParentToolCallId())
                    .subAgentId(item.getSubAgentId())
                    .subAgentType(item.getSubAgentType())
                    .subAgentDescription(item.getSubAgentDescription())
                    .dispatchIndex(item.getDispatchIndex())
                    .agentName(record.getAgentName())
                    .stepNo(record.getStepNo())
                    .toolName(item.getToolName())
                    .toolProvider(item.getToolProvider())
                    .inputJson(item.getInputJson())
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .startedAt(defaultNow(item.getStartedAt()))
                    .build());
            toolCallIds.add(item.getToolCallId());
        }
        if (entities.isEmpty()) {
            return mapping;
        }

        // 正常路径一次多值 INSERT，避免 batch-start 的 DB 往返随工具扇出线性增长。
        try {
            int inserted = executionLedgerWriteRepository.batchInsertToolInvocations(entities);
            if (inserted > 0) {
                // 多值 INSERT 要么全写要么全不写：已写入时绝不能回退逐条重插（会撞唯一键）。
                for (int i = 0; i < entities.size(); i++) {
                    if (entities.get(i).getId() != null) {
                        mapping.put(toolCallIds.get(i), entities.get(i).getId());
                    }
                }
                if (inserted == entities.size() && mapping.size() == entities.size()) {
                    markSuccess("createToolInvocation");
                } else {
                    log.error("batch createToolInvocations wrote rows but returned incomplete ids: "
                                    + "runId={}, expected={}, inserted={}, mapped={}",
                            record.getRunId(), entities.size(), inserted, mapping.size());
                }
                if (writeTimeline) {
                    recordToolTimelineItems(record, entities, toolCallIds, mapping);
                }
                return mapping;
            }
            log.warn("batch createToolInvocations wrote no rows, fallback to per-item write: runId={}, size={}",
                    record.getRunId(), entities.size());
        } catch (TimelineItemWriteException e) {
            markFailure("createToolInvocation", record.getRequestId(), record.getRunId(), null, e);
            throw e;
        } catch (Exception e) {
            // 单条批量语句整体失败（如唯一键冲突）时回退逐条写入：
            // 单个工具失败不应阻止同批其他工具获得 invocationId。
            log.warn("batch createToolInvocations failed, fallback to per-item write: runId={}, size={}",
                    record.getRunId(), entities.size(), e);
        }

        for (int i = 0; i < entities.size(); i++) {
            ToolInvocation entity = entities.get(i);
            String toolCallId = toolCallIds.get(i);
            try {
                executionLedgerWriteRepository.insertToolInvocation(entity);
                mapping.put(toolCallId, entity.getId());
                if (writeTimeline) {
                    writeToolTimelineItem(toolTimelineItem(record, entity, toolCallId), true);
                }
                markSuccess("createToolInvocation");
            } catch (TimelineItemWriteException e) {
                markFailure("createToolInvocation", record.getRequestId(), record.getRunId(), toolCallId, e);
                throw e;
            } catch (Exception e) {
                markFailure("createToolInvocation", record.getRequestId(), record.getRunId(), toolCallId, e);
            }
        }
        return mapping;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishToolInvocation(ToolInvocationFinishRecord record) {
        finishToolInvocationInternal(record, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishToolInvocationWithoutTimeline(ToolInvocationFinishRecord record) {
        finishToolInvocationInternal(record, false);
    }

    @Override
    public void snapshotToolInvocation(ToolInvocationSnapshotRecord record) {
        if (record == null || record.getStructuredOutput() == null) {
            return;
        }
        try {
            Long toolInvocationId = record.getToolInvocationId();
            if (toolInvocationId == null
                    && record.getRunId() != null
                    && StringUtils.isNotBlank(record.getToolCallId())) {
                var invocation = executionLedgerWriteRepository.queryToolInvocation(
                        record.getRunId(),
                        record.getToolCallId()
                );
                toolInvocationId = invocation == null ? null : invocation.getId();
            }
            if (toolInvocationId == null) {
                return;
            }
            toolOutputWriter.write(ToolOutputPersistCommand.builder()
                    .toolInvocationId(toolInvocationId)
                    .runId(record.getRunId())
                    .requestId(record.getRequestId())
                    .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                    .sessionId(record.getSessionId())
                    .toolCallId(record.getToolCallId())
                    .toolName(record.getToolName())
                    .status(record.getStatus())
                    .structuredOutput(record.getStructuredOutput())
                    .build());
        } catch (Exception e) {
            log.warn("tool snapshot persist failed requestId={} toolCallId={}",
                    record.getRequestId(), record.getToolCallId(), e);
        }
    }

    private void finishToolInvocationInternal(ToolInvocationFinishRecord record, boolean writeTimeline) {
        if (record == null || record.getToolInvocationId() == null) {
            return;
        }
        try {
            // 工具主表先记录通用状态；结构化输出随后按工具类型写入专用表，二者共享同一个 invocationId。
            // finishedAt 允许为 null：后台派发 ACK 是 provisional 写（子 Agent 仍在跑），
            // 必须保持 finished_at IS NULL，否则真正结算时过不了 mapper 守卫。
            executionLedgerWriteRepository.updateToolInvocationFinish(ToolInvocation.builder()
                    .id(record.getToolInvocationId())
                    .status(record.getStatus())
                    .llmObservation(record.getLlmObservation())
                    .toolResult(record.getToolResult())
                    .errorMsg(trimText(record.getErrorMsg(), 2000))
                    .finishedAt(record.getFinishedAt())
                    .build());
            if (writeTimeline) {
                ToolInvocation source =
                        executionLedgerWriteRepository.queryToolInvocationById(record.getToolInvocationId());
                DialogueRun sourceRun = source == null
                        ? null
                        : executionLedgerWriteRepository.queryRunById(source.getRunId());
                String toolCallId = StringUtils.defaultIfBlank(
                        record.getToolCallId(),
                        source == null ? null : source.getToolCallId());
                if (StringUtils.isBlank(toolCallId)) {
                    throw new IllegalStateException("tool timeline finish requires toolCallId");
                }
                String parentItemId = null;
                if (source != null
                        && StringUtils.isNotBlank(source.getParentToolCallId())
                        && executionLedgerWriteRepository.queryToolInvocation(
                                source.getRunId(), source.getParentToolCallId()) != null) {
                    parentItemId =
                            new TimelineItemKey.Tool(source.getParentToolCallId()).encode();
                }
                writeToolTimelineItem(new ToolTimelineFact(
                        new TimelineItemKey.Tool(toolCallId),
                        StringUtils.defaultIfBlank(
                                record.getSessionId(),
                                sourceRun == null ? null : sourceRun.getSessionId()),
                        StringUtils.defaultIfBlank(
                                record.getRequestId(),
                                sourceRun == null ? null : sourceRun.getRequestId()),
                        StringUtils.defaultIfBlank(
                                source == null ? null : source.getSubAgentId(),
                                "main"),
                        toTimelineStatus(record.getStatus()),
                        parentItemId,
                        record.getToolInvocationId()
                ), false);
            }
            persistStructuredOutput(record);
            markSuccess("finishToolInvocation");
        } catch (TimelineItemWriteException e) {
            markFailure("finishToolInvocation", record.getRequestId(), null, record.getToolCallId(), e);
            throw e;
        } catch (Exception e) {
            markFailure("finishToolInvocation", record.getRequestId(), null, record.getToolCallId(), e);
        }
    }

    /**
     * rich tool 输出和主账本分离，避免结构化结果继续堆在主表。
     */
    private void persistStructuredOutput(ToolInvocationFinishRecord record) {
        if (record == null
                || !ToolOutputNames.isPersistedTool(record.getToolName())
                || record.getStructuredOutput() == null) {
            return;
        }
        // 主表只保存通用状态和 observation；rich output 按工具类型落到专用表，便于查询和演进。
        toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(record.getToolInvocationId())
                .runId(record.getRunId())
                .requestId(record.getRequestId())
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId(record.getSessionId())
                .toolCallId(record.getToolCallId())
                .toolName(record.getToolName())
                .status(record.getStatus())
                .errorMsg(record.getErrorMsg())
                .structuredOutput(record.getStructuredOutput())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordArtifacts(List<ArtifactRecordCommand> records) {
        try {
            recordArtifactsOrThrow(records);
        } catch (TimelineItemWriteException e) {
            markFailure("recordArtifacts", artifactRequestId(records), artifactRunId(records), null, e);
            throw e;
        } catch (Exception e) {
            String requestId = null;
            Long runId = null;
            if (CollectionUtils.isNotEmpty(records)) {
                for (ArtifactRecordCommand record : records) {
                    if (record == null) {
                        continue;
                    }
                    requestId = requestId == null ? record.getRequestId() : requestId;
                    runId = runId == null ? record.getRunId() : runId;
                }
            }
            markFailure("recordArtifacts", requestId, runId, null, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordArtifactsOrThrow(List<ArtifactRecordCommand> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        List<ArtifactRecord> entities = new ArrayList<>(records.size());
        for (ArtifactRecordCommand record : records) {
            if (record == null || StringUtils.isBlank(record.getFileName())) {
                continue;
            }
            entities.add(ArtifactRecord.builder()
                    .runId(record.getRunId())
                    .requestId(record.getRequestId())
                    .toolInvocationId(record.getToolInvocationId())
                    .toolCallId(record.getToolCallId())
                    .artifactRole(record.getArtifactRole())
                    .visibility(record.getVisibility())
                    .sourceType(record.getSourceType())
                    .sourceName(record.getSourceName())
                    .fileName(record.getFileName())
                    .storageKey(defaultEmpty(record.getStorageKey()))
                    .downloadUrl(record.getDownloadUrl())
                    .previewUrl(record.getPreviewUrl())
                    .mimeType(record.getMimeType())
                    .fileSize(record.getFileSize())
                    .fileHash(record.getFileHash())
                    .metadataJson(record.getMetadataJson())
                    .build());
        }
        if (entities.isEmpty()) {
            return;
        }
        // artifact 是回放和前端文件展示的稳定引用，批量写入必须校验实际插入数，
        // 否则重复键或部分失败会让执行成功但历史缺文件。
        // artifact 是工具输出可回放的稳定引用，写入失败由调用方选择 fail-open 或显式抛出。
        int inserted = executionLedgerWriteRepository.batchInsertArtifacts(entities);
        if (inserted < entities.size()) {
            throw new IllegalStateException(String.format(
                    "artifact duplicate or ignored, expected=%d, inserted=%d", entities.size(), inserted));
        }
        recordArtifactTimelineItems(entities);
        markSuccess("recordArtifacts");
    }

    private void recordToolTimelineItems(ToolInvocationBatchStartRecord record,
                                         List<ToolInvocation> entities,
                                         List<String> toolCallIds,
                                         Map<String, Long> mapping) {
        if (timelineProjectionWriter == null || CollectionUtils.isEmpty(entities)) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            String toolCallId = toolCallIds.get(i);
            Long invocationId = mapping.get(toolCallId);
            if (invocationId == null) {
                continue;
            }
            ToolInvocation entity = entities.get(i);
            writeToolTimelineItem(toolTimelineItem(record, entity, toolCallId), true);
        }
    }

    private ToolTimelineFact toolTimelineItem(ToolInvocationBatchStartRecord record,
                                              ToolInvocation entity,
                                              String toolCallId) {
        DialogueRun sourceRun =
                executionLedgerWriteRepository.queryRunById(record.getRunId());
        return new ToolTimelineFact(
                new TimelineItemKey.Tool(toolCallId),
                StringUtils.defaultIfBlank(
                        record.getSessionId(),
                        sourceRun == null ? null : sourceRun.getSessionId()),
                StringUtils.defaultIfBlank(
                        record.getRequestId(),
                        sourceRun == null ? null : sourceRun.getRequestId()),
                StringUtils.defaultIfBlank(entity.getSubAgentId(), "main"),
                TimelineItemStatus.RUNNING,
                StringUtils.isNotBlank(entity.getParentToolCallId())
                        && executionLedgerWriteRepository.queryToolInvocation(
                                record.getRunId(), entity.getParentToolCallId()) != null
                        ? "tool:" + entity.getParentToolCallId()
                        : null,
                entity.getId()
        );
    }

    private void recordLlmFinishTimelineItem(LlmInvocationFinishRecord record) {
        TimelineItemStatus status = toTimelineStatus(record.getStatus());
        LlmInvocation source =
                executionLedgerWriteRepository.queryLlmInvocationById(record.getLlmInvocationId());
        DialogueRun sourceRun = source == null
                ? null
                : executionLedgerWriteRepository.queryRunById(source.getRunId());
        String agentId = StringUtils.defaultIfBlank(
                source == null ? null : source.getAgentName(),
                "main");
        String sessionId = StringUtils.defaultIfBlank(
                record.getSessionId(),
                sourceRun == null ? null : sourceRun.getSessionId());
        String requestId = StringUtils.defaultIfBlank(
                record.getRequestId(),
                sourceRun == null ? null : sourceRun.getRequestId());
        writeLlmTimelineItem(new LlmTimelineFact(
                new TimelineItemKey.Llm(record.getLlmInvocationId()),
                sessionId,
                requestId,
                agentId,
                TimelineItemPhase.INTERMEDIATE,
                status,
                null
        ), false);
        if (StringUtils.isNotBlank(record.getReasoningContent())) {
            writeLlmTimelineItem(new LlmTimelineFact(
                    new TimelineItemKey.LlmReasoning(record.getLlmInvocationId()),
                    sessionId,
                    requestId,
                    agentId,
                    TimelineItemPhase.INTERMEDIATE,
                    status,
                    new TimelineItemKey.Llm(record.getLlmInvocationId()).encode()
            ), false);
        }
    }

    private void recordRunTimelineItem(DialogueRun existing,
                                       DialogueRunFinishRecord record) {
        TimelineItemStatus status = toTimelineStatus(record.getStatus());
        if (timelineProjectionWriter == null
                || status == TimelineItemStatus.RUNNING
                || status == TimelineItemStatus.WAITING_INPUT) {
            return;
        }
        writeRunTimelineItem(new RunTimelineFact(
                new TimelineItemKey.RunFinal(record.getRequestId()),
                existing.getSessionId(),
                record.getRequestId(),
                "main",
                status
        ));
    }

    private void recordArtifactTimelineItems(List<ArtifactRecord> entities) {
        if (timelineProjectionWriter == null || CollectionUtils.isEmpty(entities)) {
            return;
        }
        Map<String, DialogueRun> runByRequestId = new HashMap<>();
        List<ArtifactTimelineFact> facts = new ArrayList<>(entities.size());
        for (ArtifactRecord persisted : entities) {
            if (persisted == null
                    || persisted.getId() == null
                    || !ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equalsIgnoreCase(
                    persisted.getArtifactRole())
                    || !ExecutionLedgerConstants.VISIBILITY_VISIBLE.equalsIgnoreCase(
                    persisted.getVisibility())) {
                continue;
            }
            if (StringUtils.isBlank(persisted.getToolCallId())
                    || persisted.getToolInvocationId() == null) {
                log.warn("skip artifact timeline without tool identity, artifactId={}, requestId={}",
                        persisted.getId(), persisted.getRequestId());
                continue;
            }
            DialogueRun run = null;
            if (StringUtils.isNotBlank(persisted.getRequestId())) {
                if (!runByRequestId.containsKey(persisted.getRequestId())) {
                    runByRequestId.put(
                            persisted.getRequestId(),
                            executionLedgerWriteRepository.queryRunByRequestId(persisted.getRequestId()));
                }
                run = runByRequestId.get(persisted.getRequestId());
            }
            if (run == null) {
                continue;
            }
            facts.add(new ArtifactTimelineFact(
                    new TimelineItemKey.Artifact(persisted.getId()),
                    run.getSessionId(),
                    persisted.getRequestId(),
                    resolveArtifactAgentId(persisted.getToolInvocationId()),
                    new TimelineItemKey.Tool(persisted.getToolCallId()).encode(),
                    persisted.getToolInvocationId()
            ));
        }
        if (!facts.isEmpty()) {
            try {
                timelineProjectionWriter.writeArtifacts(facts);
            } catch (TimelineItemWriteException e) {
                throw e;
            } catch (Exception e) {
                throw new TimelineItemWriteException("artifact timeline write failed", e);
            }
        }
    }

    private void writeLlmTimelineItem(LlmTimelineFact fact, boolean started) {
        if (timelineProjectionWriter == null) {
            return;
        }
        try {
            if (started) {
                timelineProjectionWriter.writeLlmStarted(fact);
            } else {
                timelineProjectionWriter.writeLlmFinished(fact);
            }
        } catch (TimelineItemWriteException e) {
            throw e;
        } catch (Exception e) {
            throw new TimelineItemWriteException("llm timeline write failed: "
                    + fact.key().encode(), e);
        }
    }

    private void writeToolTimelineItem(ToolTimelineFact fact, boolean started) {
        if (timelineProjectionWriter == null) {
            return;
        }
        try {
            if (started) {
                timelineProjectionWriter.writeToolStarted(fact);
            } else {
                timelineProjectionWriter.writeToolFinished(fact);
            }
        } catch (TimelineItemWriteException e) {
            throw e;
        } catch (Exception e) {
            throw new TimelineItemWriteException("tool timeline write failed: "
                    + fact.key().encode(), e);
        }
    }

    private void writeRunTimelineItem(RunTimelineFact fact) {
        if (timelineProjectionWriter == null) {
            return;
        }
        try {
            timelineProjectionWriter.writeRunFinal(fact);
        } catch (TimelineItemWriteException e) {
            throw e;
        } catch (Exception e) {
            throw new TimelineItemWriteException("run timeline write failed: "
                    + fact.key().encode(), e);
        }
    }

    private String resolveArtifactAgentId(Long toolInvocationId) {
        ToolInvocation tool =
                executionLedgerWriteRepository.queryToolInvocationById(toolInvocationId);
        return StringUtils.defaultIfBlank(
                tool == null ? null : tool.getSubAgentId(),
                "main");
    }

    private TimelineItemStatus toTimelineStatus(Integer ledgerStatus) {
        int status = defaultZero(ledgerStatus);
        if (status == ExecutionLedgerConstants.STATUS_SUCCESS) {
            return TimelineItemStatus.COMPLETED;
        }
        if (status == ExecutionLedgerConstants.STATUS_STOPPED) {
            return TimelineItemStatus.CANCELLED;
        }
        if (status == ExecutionLedgerConstants.STATUS_WAITING_INPUT) {
            return TimelineItemStatus.WAITING_INPUT;
        }
        return status == ExecutionLedgerConstants.STATUS_RUNNING
                ? TimelineItemStatus.RUNNING
                : TimelineItemStatus.FAILED;
    }

    private int sumPromptTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getPromptTokens());
        }
        return total;
    }

    private int sumCompletionTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getCompletionTokens());
        }
        return total;
    }

    private int sumTotalTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getTotalTokens());
        }
        return total;
    }

    private int sizeOf(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private String artifactRequestId(List<ArtifactRecordCommand> records) {
        if (CollectionUtils.isEmpty(records)) {
            return null;
        }
        for (ArtifactRecordCommand record : records) {
            if (record != null && StringUtils.isNotBlank(record.getRequestId())) {
                return record.getRequestId();
            }
        }
        return null;
    }

    private Long artifactRunId(List<ArtifactRecordCommand> records) {
        if (CollectionUtils.isEmpty(records)) {
            return null;
        }
        for (ArtifactRecordCommand record : records) {
            if (record != null && record.getRunId() != null) {
                return record.getRunId();
            }
        }
        return null;
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String defaultEmpty(String value) {
        return value == null ? "" : value;
    }

    private LocalDateTime defaultNow(LocalDateTime value) {
        return value != null ? value : LocalDateTime.now();
    }

    private Long calculateDuration(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    private String trimText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private void markSuccess(String scene) {
        successCounters.computeIfAbsent(scene, key -> new LongAdder()).increment();
    }

    private void markFailure(String scene, String requestId, Long runId, String toolCallId, Exception e) {
        // 复用 computeIfAbsent 返回的 adder，避免 getOrDefault 每次分配一次性 LongAdder 再做第二次查表。
        LongAdder failureCounter = failureCounters.computeIfAbsent(scene, key -> new LongAdder());
        failureCounter.increment();
        LongAdder successCounter = successCounters.get(scene);
        long success = successCounter == null ? 0L : successCounter.sum();
        long failure = failureCounter.sum();
        double successRate = (success + failure) == 0 ? 1D : (double) success / (success + failure);
        log.error("Execution ledger {} failed, requestId={}, runId={}, toolCallId={}, successRate={}",
                scene, requestId, runId, toolCallId, String.format("%.4f", successRate), e);
    }

    private String resolveSessionTitle(String queryText) {
        String normalized = StringUtils.trimToEmpty(queryText);
        if (normalized.isEmpty()) {
            return ExecutionLedgerConstants.DEFAULT_SESSION_TITLE;
        }
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    /**
     * continuation run 的 query 按协议为空，不能覆盖首轮会话标题和问题预览。
     * 同时兼容已经被旧逻辑覆盖成“新对话”的历史 session：从首个非空 run query 恢复展示值。
     */
    private SessionHeadValues resolveSessionHead(String sessionId, String queryText) {
        if (StringUtils.isNotBlank(queryText)) {
            return new SessionHeadValues(
                    resolveSessionTitle(queryText),
                    queryText
            );
        }

        DialogueSession existing = StringUtils.isBlank(sessionId)
                ? null
                : executionLedgerWriteRepository.querySessionBySessionId(sessionId);
        String previousQuery = existing == null ? null : existing.getLatestQueryText();
        if (StringUtils.isBlank(previousQuery) && StringUtils.isNotBlank(sessionId)) {
            previousQuery = resolveFirstNonBlankQuery(sessionId);
        }

        String existingTitle = existing == null ? null : existing.getTitle();
        String title = isMeaningfulSessionTitle(existingTitle)
                ? existingTitle
                : resolveSessionTitle(previousQuery);
        String latestQueryText = StringUtils.defaultIfBlank(previousQuery, "");
        return new SessionHeadValues(title, latestQueryText);
    }

    private String resolveFirstNonBlankQuery(String sessionId) {
        List<DialogueRunView> runs = executionLedgerWriteRepository.queryRunsBySessionId(sessionId);
        if (CollectionUtils.isEmpty(runs)) {
            return null;
        }
        for (DialogueRunView run : runs) {
            if (run != null && StringUtils.isNotBlank(run.getQueryText())) {
                return run.getQueryText();
            }
        }
        return null;
    }

    private boolean isMeaningfulSessionTitle(String title) {
        return StringUtils.isNotBlank(title) && !ExecutionLedgerConstants.DEFAULT_SESSION_TITLE.equals(title.trim());
    }

    private record SessionHeadValues(String title, String latestQueryText) {
    }

    private boolean isSuccessStatus(Integer status) {
        return defaultZero(status) == ExecutionLedgerConstants.STATUS_SUCCESS;
    }

    private boolean isFailedStatus(Integer status) {
        int normalizedStatus = defaultZero(status);
        return normalizedStatus == ExecutionLedgerConstants.STATUS_FAILED
                || normalizedStatus == ExecutionLedgerConstants.STATUS_TIMEOUT
                || normalizedStatus == ExecutionLedgerConstants.STATUS_STOPPED;
    }
}
