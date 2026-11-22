package org.wwz.ai.application.agent.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.query.GptQueryApplicationService;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.AgentTimelineFrameEnricher;
import org.wwz.ai.application.agent.stream.SessionEventClock;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 空闲唤醒：为一个已有通知排队的会话开一轮新的 run。
 * <p>逐句对齐 {@link org.wwz.ai.application.agent.askuser.AskUserResumeApplicationService}，
 * 但不做交互鉴权、不要求非空 query——唤醒轮的 query 为空，通知在第一步由 BaseAgent drain。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundTaskAutoContinuationApplicationService {

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final IAgentDispatchService agentDispatchService;
    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    private final AgentSessionEventBus agentSessionEventBus;
    private final AgentRunLaunchGate agentRunLaunchGate;
    private final SessionEventClock sessionEventClock;
    private final SessionProjectionRegistry sessionProjectionRegistry;
    private final CuteConfig cuteConfig;

    @Resource
    private Map<AgentType, AgentStreamEventHandler> handlerMap;

    @Resource
    @Qualifier(AgentExecutorNames.DISPATCH_EXECUTOR)
    private Executor dispatchExecutor;

    @Resource
    private AgentTimelineFrameEnricher agentTimelineFrameEnricher;

    @Autowired(required = false)
    private SessionInboxPort sessionInboxPort;

    @Resource
    private TasklistPersistencePort tasklistPersistencePort;

    @Resource
    private CuteRuntimeDependencies cuteRuntimeDependencies;

    @Resource
    private AgentExecutionRecorder agentExecutionRecorder;

    /**
     * @param displayQuery 唤醒控制帧展示文本；实际投递仍以 Session Inbox claim 为准。
     * @param onRunSettled 唤醒轮收口回调，参数是本次唤醒 requestId。
     */
    public boolean dispatchWake(String sessionId,
                                String taskId,
                                String triggerSource,
                                String displayQuery,
                                Consumer<String> onRunSettled) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        DialogueSession session;
        try {
            session = executionLedgerReadRepository.querySessionEntity(sessionId);
        } catch (Exception e) {
            log.warn("background wake: session query failed sessionId={}", sessionId, e);
            return false;
        }
        if (session == null) {
            log.warn("background wake: session not found sessionId={}", sessionId);
            return false;
        }
        boolean subAgent = SessionKind.SUBAGENT.code().equals(session.getSessionKind());
        RuntimeBackgroundTask logicalTask = subAgent
                ? tasklistPersistencePort.findActiveBackgroundTaskByChildSessionId(sessionId).orElse(null)
                : null;
        // 已结束逻辑任务的子会话仍可能被用户直聊，并以自身作为新的本地树根
        // 派发后台孙 Agent。没有 active logicalTask 时照常唤醒，只是不回传旧父。
        AgentExecutionCommand request = buildWakeRequest(session, triggerSource, displayQuery, subAgent);
        try {
            activeAgentRunRegistry.begin(request.getRequestId(), sessionId, request.getUserId());
        } catch (AgentConcurrentRunException e) {
            log.info("background wake skipped, session busy sessionId={} activeRequestId={}",
                    sessionId, e.getActiveRequestId());
            return false;
        }
        if (logicalTask != null && !registryFor(logicalTask).tryBeginClosingTurn(logicalTask.getId())) {
            activeAgentRunRegistry.end(request.getRequestId());
            failClosingTurnExhausted(logicalTask);
            return false;
        }

        AgentStreamProjection projectingStream =
                new AgentStreamProjection(null, request, handlerMap, agentSessionEventBus,
                        sessionEventClock, agentTimelineFrameEnricher)
                        .bindRegistry(sessionProjectionRegistry);
        // 先发控制帧，前端据此建占位 chat 并重新订阅；再结束旧投影的观察流。
        publishWakeFrame(sessionId, request.getRequestId(), taskId, triggerSource, displayQuery);
        StaleSessionProjections.complete(
                sessionId, sessionProjectionRegistry.listLive(sessionId), projectingStream, log);

        try {
            agentRunLaunchGate.defer(request.getRequestId(), sessionId, () -> {
                try {
                    AgentExecutorSupport.execute(dispatchExecutor, "backgroundTaskWake", request.getRequestId(),
                            () -> dispatch(request, projectingStream, onRunSettled, logicalTask));
                } catch (AgentExecutorBusyException e) {
                    log.warn("background wake deferred launch busy requestId={}", request.getRequestId(), e);
                    failWake(sessionId, request.getRequestId(), projectingStream, logicalTask, true, e);
                } catch (RuntimeException e) {
                    log.error("background wake deferred launch failed requestId={}", request.getRequestId(), e);
                    failWake(sessionId, request.getRequestId(), projectingStream, logicalTask, true, e);
                }
            });
        } catch (RuntimeException e) {
            log.error("background wake launch scheduling failed requestId={}", request.getRequestId(), e);
            failWake(sessionId, request.getRequestId(), projectingStream, logicalTask, true, e);
        }
        log.info("background wake dispatched sessionId={} requestId={} taskId={}",
                sessionId, request.getRequestId(), taskId);
        return true;
    }

    private void dispatch(AgentExecutionCommand request,
                          AgentStreamProjection projectingStream,
                          Consumer<String> onRunSettled,
                          RuntimeBackgroundTask logicalTask) {
        boolean runStarted = false;
        try {
            agentDispatchService.dispatch(request, projectingStream);
            runStarted = true;
            GptQueryApplicationService.completeProjectionUnlessBackgroundRunning(
                    request, projectingStream, activeAgentRunRegistry, sessionInboxPort);
        } catch (Exception e) {
            log.error("{} background wake failed", request.getRequestId(), e);
            failWake(request.getSessionId(), request.getRequestId(), projectingStream, logicalTask, !runStarted, e);
        } finally {
            // 唤醒轮也是普通 run：释放 ActiveRun 后必须重新判活，避免终答期间到达的通知滞留。
            if (runStarted) {
                if (logicalTask != null) {
                    try {
                        settleSubAgentIfQuiet(request);
                    } catch (Exception settleError) {
                        log.error("{} background wake settle failed taskId={}",
                                request.getRequestId(), logicalTask.getId(), settleError);
                        failSettlement(logicalTask, settleError);
                    }
                }
                notifyRunSettled(request.getRequestId(), onRunSettled);
            }
        }
    }

    private void settleSubAgentIfQuiet(AgentExecutionCommand request) {
        if (request == null || tasklistPersistencePort == null) {
            return;
        }
        Optional<RuntimeBackgroundTask> found =
                tasklistPersistencePort.findActiveBackgroundTaskByChildSessionId(request.getSessionId());
        if (found.isEmpty()) {
            return;
        }
        RuntimeBackgroundTask task = found.get();
        RuntimeBackgroundTaskRegistry registry = registryFor(task);
        if (!registry.listRunningOwnedBy(task.getAgentId()).isEmpty()
                || registry.hasUnconsumedOwnedBy(task.getAgentId())) {
            registry.markWaiting(task.getId());
            return;
        }
        if (sessionInboxPort != null
                && (sessionInboxPort.hasDeliverableKind(request.getSessionId(),
                        SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                || sessionInboxPort.hasDeliverableKind(request.getSessionId(),
                        SessionInboxItem.KIND_USER_MESSAGE))) {
            registry.markWaiting(task.getId());
            return;
        }
        DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(request.getRequestId());
        if (run == null || !run.isTerminal()) {
            return;
        }
        boolean completed = run.getStatus() != null
                && run.getStatus() == org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS;
        SubAgentResult result = SubAgentResult.builder()
                .status(completed ? SubAgentResult.STATUS_COMPLETED : SubAgentResult.STATUS_FAILED)
                .agentId(task.getAgentId())
                .agentType(task.getAgentType())
                .childSessionId(task.getChildSessionId())
                .description(task.getDescription())
                .prompt(task.getPrompt())
                .content(run.getFinalSummaryText())
                .errorMsg(completed ? null : run.getErrorMsg())
                .totalDurationMs(task.getTotalDurationMs())
                .build();
        BackgroundSubAgentExecutor.completePersistedTask(
                task, result, cuteRuntimeDependencies, agentExecutionRecorder, executionLedgerReadRepository);
    }

    private void notifyRunSettled(String requestId, Consumer<String> onRunSettled) {
        if (onRunSettled == null) {
            return;
        }
        try {
            onRunSettled.accept(requestId);
        } catch (Exception e) {
            log.warn("background wake settle check failed", e);
        }
    }

    private void failWake(String sessionId,
                          String requestId,
                          AgentStreamProjection projectingStream,
                          RuntimeBackgroundTask logicalTask,
                          boolean rollbackClosingTurn,
                          Exception failure) {
        // 先发失败控制帧，再关流；否则 completeWithError 会先把会话连接收掉。
        publishWakeFailed(sessionId, requestId, failure);
        try {
            projectingStream.completeWithError(failure);
        } finally {
            activeAgentRunRegistry.end(requestId);
        }
        List<AgentStreamProjection> stale = sessionProjectionRegistry.listLive(sessionId);
        if (activeAgentRunRegistry.findBySessionId(sessionId).isPresent()) {
            return;
        }
        restoreWaiting(logicalTask, rollbackClosingTurn);
        // 只收口快照内的旧投影；检查期间新建的投影留给接班的 run。
        StaleSessionProjections.complete(sessionId, stale, projectingStream, log);
    }

    private RuntimeBackgroundTaskRegistry registryFor(RuntimeBackgroundTask task) {
        return SessionBackgroundTaskHub.getOrCreate(
                StringUtils.defaultIfBlank(task.getRootSessionId(), task.getParentSessionId()),
                tasklistPersistencePort);
    }

    private void restoreWaiting(RuntimeBackgroundTask task, boolean rollbackClosingTurn) {
        if (task == null) {
            return;
        }
        try {
            RuntimeBackgroundTaskRegistry registry = registryFor(task);
            if (rollbackClosingTurn) {
                registry.rollbackClosingTurn(task.getId());
            }
            registry.markWaiting(task.getId());
        } catch (Exception e) {
            log.warn("background wake failed to restore waiting taskId={}", task.getId(), e);
        }
    }

    private void failClosingTurnExhausted(RuntimeBackgroundTask task) {
        SubAgentResult result = SubAgentResult.builder()
                .status(SubAgentResult.STATUS_FAILED)
                .agentId(task.getAgentId())
                .agentType(task.getAgentType())
                .childSessionId(task.getChildSessionId())
                .description(task.getDescription())
                .prompt(task.getPrompt())
                .errorMsg("后台收尾轮数已达上限，子 Agent 已终止")
                .build();
        BackgroundSubAgentExecutor.failPersistedTask(
                task,
                result,
                cuteRuntimeDependencies,
                agentExecutionRecorder,
                executionLedgerReadRepository);
    }

    private void failSettlement(RuntimeBackgroundTask task, Exception failure) {
        String reason = failure == null || StringUtils.isBlank(failure.getMessage())
                ? "后台子 Agent 收口失败"
                : "后台子 Agent 收口失败：" + failure.getMessage();
        SubAgentResult result = SubAgentResult.builder()
                .status(SubAgentResult.STATUS_FAILED)
                .agentId(task.getAgentId())
                .agentType(task.getAgentType())
                .childSessionId(task.getChildSessionId())
                .description(task.getDescription())
                .prompt(task.getPrompt())
                .errorMsg(reason)
                .build();
        try {
            BackgroundSubAgentExecutor.failPersistedTask(
                    task,
                    result,
                    cuteRuntimeDependencies,
                    agentExecutionRecorder,
                    executionLedgerReadRepository);
        } catch (Exception terminalError) {
            log.error("background wake failed to persist terminal error taskId={}",
                    task.getId(), terminalError);
        }
    }

    private AgentExecutionCommand buildWakeRequest(DialogueSession session,
                                                   String triggerSource,
                                                   String displayQuery,
                                                   boolean subAgent) {
        org.wwz.ai.domain.agent.runtime.enums.CollaborationMode mode =
                org.wwz.ai.domain.agent.runtime.enums.CollaborationMode.from(session.getCollaborationMode());
        boolean cluster = mode.isCluster();
        String basePrompt = cluster || subAgent
                ? ""
                : StringUtils.defaultString(cuteConfig.getCuteBasePrompt());
        return AgentExecutionCommand.builder()
                .requestId(newRequestId())
                .sessionId(session.getSessionId())
                .userId(session.getUserId())
                .query("")
                .ledgerQueryText(displayQuery)
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .agentType(cluster && !subAgent
                        ? AgentType.PLAN_SOLVE.getValue()
                        : AgentType.REACT.getValue())
                .collaborationMode(mode.getValue())
                .mainAgentKey(session.getMainAgentKey())
                .promptSnapshot(session.getPromptSnapshot())
                .promptVersion(session.getPromptVersion())
                .promptHash(session.getPromptHash())
                .basePrompt(basePrompt)
                .isStream(true)
                .triggerSource(StringUtils.defaultIfBlank(triggerSource, "background_task"))
                .build();
    }

    private void publishWakeFrame(String sessionId,
                                  String requestId,
                                  String taskId,
                                  String triggerSource,
                                  String displayQuery) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("requestId", requestId);
        resultMap.put("sessionId", sessionId);
        resultMap.put("triggerSource", StringUtils.defaultIfBlank(triggerSource, "background_task"));
        if (StringUtils.isNotBlank(taskId)) {
            resultMap.put("taskId", taskId);
        }
        if (StringUtils.isNotBlank(displayQuery)) {
            resultMap.put("query", displayQuery);
        }
        agentSessionEventBus.publish(sessionId, AgentSessionStreamFrame.builder()
                .reqId(requestId)
                .packageType("run_wake")
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType("run.wake")
                .eventId(TimelineItemProjection.eventId(sessionId, 0, "run_wake:" + requestId))
                .runId(requestId)
                .status("success")
                .finished(false)
                .responseType("text")
                .resultMap(resultMap)
                .build());
    }

    private void publishWakeFailed(String sessionId, String requestId, Exception failure) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("requestId", requestId);
        resultMap.put("sessionId", sessionId);
        if (failure != null) {
            log.warn("background task wake failed, sessionId={}, requestId={}",
                    sessionId, requestId, failure);
        }
        agentSessionEventBus.publish(sessionId, AgentSessionStreamFrame.builder()
                .reqId(requestId)
                .packageType("run_wake_failed")
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType("run.wake_failed")
                .eventId(TimelineItemProjection.eventId(sessionId, 0, "run_wake_failed:" + requestId))
                .runId(requestId)
                .status("failed")
                .finished(true)
                .errorMsg("后台续跑启动失败，请稍后重试")
                .resultMap(resultMap)
                .build());
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
