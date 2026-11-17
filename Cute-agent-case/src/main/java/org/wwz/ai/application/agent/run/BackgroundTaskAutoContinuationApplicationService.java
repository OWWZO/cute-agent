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
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (SessionKind.SUBAGENT.code().equals(session.getSessionKind())) {
            return false;
        }

        AgentExecutionCommand request = buildWakeRequest(session, triggerSource, displayQuery);
        try {
            activeAgentRunRegistry.begin(request.getRequestId(), sessionId, request.getUserId());
        } catch (AgentConcurrentRunException e) {
            log.info("background wake skipped, session busy sessionId={} activeRequestId={}",
                    sessionId, e.getActiveRequestId());
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
                            () -> dispatch(request, projectingStream, onRunSettled));
                } catch (AgentExecutorBusyException e) {
                    log.warn("background wake deferred launch busy requestId={}", request.getRequestId(), e);
                    failWake(sessionId, request.getRequestId(), projectingStream, e);
                } catch (RuntimeException e) {
                    log.error("background wake deferred launch failed requestId={}", request.getRequestId(), e);
                    failWake(sessionId, request.getRequestId(), projectingStream, e);
                }
            });
        } catch (RuntimeException e) {
            log.error("background wake launch scheduling failed requestId={}", request.getRequestId(), e);
            failWake(sessionId, request.getRequestId(), projectingStream, e);
        }
        log.info("background wake dispatched sessionId={} requestId={} taskId={}",
                sessionId, request.getRequestId(), taskId);
        return true;
    }

    private void dispatch(AgentExecutionCommand request,
                          AgentStreamProjection projectingStream,
                          Consumer<String> onRunSettled) {
        boolean runStarted = false;
        try {
            agentDispatchService.dispatch(request, projectingStream);
            runStarted = true;
            GptQueryApplicationService.completeProjectionUnlessBackgroundRunning(
                    request, projectingStream, activeAgentRunRegistry, sessionInboxPort);
        } catch (Exception e) {
            log.error("{} background wake failed", request.getRequestId(), e);
            failWake(request.getSessionId(), request.getRequestId(), projectingStream, e);
        } finally {
            // 唤醒轮也是普通 run：释放 ActiveRun 后必须重新判活，避免终答期间到达的通知滞留。
            if (runStarted) {
                notifyRunSettled(request.getRequestId(), onRunSettled);
            }
        }
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
        // 只收口快照内的旧投影；检查期间新建的投影留给接班的 run。
        StaleSessionProjections.complete(sessionId, stale, projectingStream, log);
    }

    private AgentExecutionCommand buildWakeRequest(DialogueSession session,
                                                   String triggerSource,
                                                   String displayQuery) {
        boolean deepThink = session.getDeepThink() != null && session.getDeepThink() != 0;
        String basePrompt = deepThink ? "" : StringUtils.defaultString(cuteConfig.getCuteBasePrompt());
        return AgentExecutionCommand.builder()
                .requestId(newRequestId())
                .sessionId(session.getSessionId())
                .userId(session.getUserId())
                .query("")
                .ledgerQueryText(displayQuery)
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .agentType(deepThink ? AgentType.PLAN_SOLVE.getValue() : AgentType.REACT.getValue())
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
