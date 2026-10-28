package org.wwz.ai.application.agent.query;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.askuser.AskUserQuestionApplicationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalApplicationService;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.SessionEventClock;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.application.agent.query.mapper.AgentExecutionCommandMapper;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelCatalog;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;
import org.wwz.ai.types.agent.user.UserRequestContext;

import javax.annotation.Resource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * GPT 查询应用服务。
 * POST 只提交 run；SSE 由 session Hub 旁观。
 */
@Slf4j
@Service
public class GptQueryApplicationService implements IGptQueryApplicationService {

    @Resource
    private AgentExecutionCommandMapper agentExecutionCommandMapper;

    @Resource
    private LlmModelCatalog llmModelCatalog;

    @Resource
    private IAgentDispatchService agentDispatchService;

    @Resource
    private ConversationSessionAuthorizationService conversationSessionAuthorizationService;

    @Resource
    private ConversationSessionPreferencesApplicationService conversationSessionPreferencesApplicationService;

    @Resource
    private AskUserQuestionApplicationService askUserQuestionApplicationService;

    @Resource
    private PlanApprovalApplicationService planApprovalApplicationService;

    @Resource
    private Map<AgentType, AgentStreamEventHandler> handlerMap;

    @Resource
    @Qualifier(AgentExecutorNames.DISPATCH_EXECUTOR)
    private Executor dispatchExecutor;

    @Resource
    private ActiveAgentRunRegistry activeAgentRunRegistry;

    @Resource
    private AgentSessionEventBus agentSessionEventBus;

    @Resource
    private AgentRunLaunchGate agentRunLaunchGate;

    @Resource
    private SessionEventClock sessionEventClock;

    @Resource
    private SessionProjectionRegistry sessionProjectionRegistry;

    @Resource
    private IExecutionLedgerReadRepository executionLedgerReadRepository;

    @Override
    public AgentQuerySubmitResult submitAgentQuery(GptQueryCommand params) {
        AgentExecutionCommand agentRequest = agentExecutionCommandMapper.toExecutionCommand(
                params, UserRequestContext.currentUserId());
        validateRequestedModel(agentRequest);
        log.info("{} start handle Agent request: {}", params.getRequestId(), JSON.toJSONString(agentRequest));

        String userId = resolveUserId(agentRequest);
        agentRequest.setUserId(userId);
        conversationSessionAuthorizationService.ensureSessionAccessible(
                userId,
                agentRequest.getSessionId(),
                agentRequest.getQuery()
        );
        // 会话级偏好落库：用户本轮显式选择的值，未传的列保持库里原值（COALESCE）。
        // 放在鉴权之后——归属未确认前不该写库。
        conversationSessionPreferencesApplicationService.upsertPreferences(
                agentRequest.getSessionId(),
                agentRequest.getModel(),
                agentRequest.getThinking(),
                agentRequest.getThinkingEffort(),
                params.getDeepThink());
        if (StringUtils.isBlank(agentRequest.getResumeQuestionId())
                && StringUtils.isBlank(agentRequest.getResumeApprovalId())
                && askUserQuestionApplicationService != null
                && askUserQuestionApplicationService.hasOpenQuestion(agentRequest.getSessionId())) {
            throw new IllegalStateException("当前会话有待回答的问题，请先回答或取消后再发送新消息");
        }
        if (StringUtils.isBlank(agentRequest.getResumeQuestionId())
                && StringUtils.isBlank(agentRequest.getResumeApprovalId())
                && planApprovalApplicationService != null
                && planApprovalApplicationService.hasOpenApproval(agentRequest.getSessionId())) {
            throw new IllegalStateException("当前会话有待批准的计划，请先批准/拒绝或取消后再发送新消息");
        }

        activeAgentRunRegistry.begin(
                agentRequest.getRequestId(),
                agentRequest.getSessionId(),
                userId);

        scheduleDispatch(agentRequest, userId);
        return AgentQuerySubmitResult.builder()
                .accepted(true)
                .sessionId(agentRequest.getSessionId())
                .requestId(agentRequest.getRequestId())
                .build();
    }

    @Override
    public AgentQuerySubmitResult submitSessionQuery(String sessionId,
                                                     String query,
                                                     SessionPreferences preferences) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("sessionId 和 query 不能为空");
        }
        String userId = UserRequestContext.currentUserId();
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        DialogueSession session = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (session == null) {
            throw new SessionOwnershipDeniedException("当前会话不存在");
        }
        conversationSessionAuthorizationService.ensureInteractiveSessionAccessible(userId, sessionId);

        SessionPreferences chosen = preferences == null ? SessionPreferences.none() : preferences;
        // 用户本轮显式选择优先；没选则沿用会话元数据。父 Agent 已结束、内存上下文被回收后，
        // 库里这四列是唯一能还原「上次用的模型/思考档位」的来源。
        String model = chosen.model() != null ? chosen.model() : session.getModel();
        Boolean thinking = chosen.thinking() != null ? chosen.thinking() : session.getThinking();
        String thinkingEffort = chosen.thinkingEffort() != null
                ? chosen.thinkingEffort()
                : session.getThinkingEffort();
        Integer deepThinkFlag = chosen.deepThink() != null ? chosen.deepThink() : session.getDeepThink();
        boolean deepThink = deepThinkFlag != null && deepThinkFlag != 0;

        AgentExecutionCommand agentRequest = AgentExecutionCommand.builder()
                .requestId(newRequestId())
                .sessionId(sessionId)
                .userId(userId)
                .query(query)
                .model(model)
                .thinking(thinking)
                .thinkingEffort(thinkingEffort)
                .agentType(deepThink ? AgentType.PLAN_SOLVE.getValue() : AgentType.REACT.getValue())
                .isStream(Boolean.TRUE)
                .build();
        validateRequestedModel(agentRequest);
        // 只落库用户本轮真正选过的值：未选时不写库，SQL 的 COALESCE 保留库里原值。
        if (chosen.hasAny()) {
            conversationSessionPreferencesApplicationService.upsertPreferences(
                    sessionId,
                    chosen.model(),
                    chosen.thinking(),
                    chosen.thinkingEffort(),
                    chosen.deepThink());
        }

        activeAgentRunRegistry.begin(agentRequest.getRequestId(), sessionId, userId);
        scheduleDispatch(agentRequest, userId);
        return AgentQuerySubmitResult.builder()
                .accepted(true)
                .sessionId(sessionId)
                .requestId(agentRequest.getRequestId())
                .build();
    }

    /**
     * 两个入口共用的派发路径：launch gate（首个观察者提前 launch）+ 投影 + 执行器。
     * <p>不要另写一套异步派发——那会绕开 gate 的延迟启动语义。</p>
     */
    private void scheduleDispatch(AgentExecutionCommand agentRequest, String userId) {
        AgentStreamProjection projectingStream =
                new AgentStreamProjection(null, agentRequest, handlerMap, agentSessionEventBus, sessionEventClock)
                        .bindRegistry(sessionProjectionRegistry);
        agentRunLaunchGate.defer(agentRequest.getRequestId(), agentRequest.getSessionId(), () -> {
            try {
                AgentExecutorSupport.execute(dispatchExecutor, "dispatch", agentRequest.getRequestId(),
                        () -> dispatchOnExecutor(agentRequest, projectingStream));
            } catch (AgentExecutorBusyException e) {
                log.warn("{} deferred dispatch busy", agentRequest.getRequestId(), e);
                activeAgentRunRegistry.end(agentRequest.getRequestId());
                projectingStream.completeWithError(e);
            }
        });
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 显式模型必须来自登录用户可见目录；空值/default 继续交给运行时选择默认模型。
     * <p>该校验必须发生在 begin 前，避免非法模型生成无法执行的 run。</p>
     */
    private void validateRequestedModel(AgentExecutionCommand agentRequest) {
        String model = agentRequest == null ? null : StringUtils.trimToNull(agentRequest.getModel());
        if (model == null || LlmModelCatalog.DEFAULT_MODEL.equalsIgnoreCase(model)) {
            return;
        }
        if (llmModelCatalog == null || !llmModelCatalog.isUserSelectableModel(model)) {
            throw new IllegalArgumentException("所选模型不可用，请从模型目录中选择已启用模型");
        }
    }

    private void dispatchOnExecutor(AgentExecutionCommand agentRequest,
                                    AgentStreamProjection projectingStream) {
        try {
            agentDispatchService.dispatch(agentRequest, projectingStream);
            completeProjectionUnlessBackgroundRunning(agentRequest, projectingStream, activeAgentRunRegistry);
        } catch (Exception e) {
            if (projectingStream.isAborted()) {
                log.info("{} dispatch error occurred after projection closed", agentRequest.getRequestId());
                if (!shouldDeferProjectionComplete(agentRequest)) {
                    projectingStream.complete();
                }
                endRunUnlessBackground(agentRequest, activeAgentRunRegistry);
                return;
            }
            log.error("{} direct dispatch error", agentRequest.getRequestId(), e);
            projectingStream.completeWithError(e);
            endRunUnlessBackground(agentRequest, activeAgentRunRegistry);
        } finally {
            log.info("{}, agent.query.web.singleRequest end, requestId: {}",
                    agentRequest.getRequestId(), JSON.toJSONString(agentRequest));
        }
    }

    private String resolveUserId(AgentExecutionCommand request) {
        String contextUserId = UserRequestContext.currentUserId();
        String userId = StringUtils.defaultIfBlank(contextUserId, request == null ? null : request.getUserId());
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        return userId;
    }

    public static void completeProjectionUnlessBackgroundRunning(AgentExecutionCommand agentRequest,
                                                                 AgentStreamProjection projectingStream) {
        completeProjectionUnlessBackgroundRunning(agentRequest, projectingStream, null);
    }

    public static void completeProjectionUnlessBackgroundRunning(AgentExecutionCommand agentRequest,
                                                                 AgentStreamProjection projectingStream,
                                                                 ActiveAgentRunRegistry runRegistry) {
        if (shouldDeferProjectionComplete(agentRequest)) {
            log.info("{} defer projection complete: background tasks still running sessionId={}",
                    agentRequest == null ? "-" : agentRequest.getRequestId(),
                    agentRequest == null ? null : agentRequest.getSessionId());
            endOccupancy(agentRequest, runRegistry);
            return;
        }
        if (projectingStream != null) {
            projectingStream.complete();
        }
        endOccupancy(agentRequest, runRegistry);
    }

    public static boolean shouldDeferProjectionComplete(AgentExecutionCommand agentRequest) {
        // 传输层默认挂起（deferBackgroundSettle=true）：后台在飞时投影保持打开，
        // 结算 tool_result / stream_settle 才有活连接推送到前端（ZCode delta 推送的等价物），
        // 父卡秒级刷新。业务态不受影响——root result 帧恒 finish，前端 loading 已立即收口。
        // flag=false 时全解耦：root result 即 complete，父卡改由前端冷路径刷新。
        if (agentRequest == null || !StreamSettlePolicy.deferBackgroundSettle()) {
            return false;
        }
        return SessionBackgroundTaskHub.hasRunning(
                SessionBackgroundTaskHub.keyFor(agentRequest.getSessionId(), agentRequest.getRequestId()));
    }

    /**
     * 父循环结束后立即释放占用槽，即使后台子 Agent 还在跑。
     */
    public static void endRunUnlessBackground(AgentExecutionCommand agentRequest, ActiveAgentRunRegistry runRegistry) {
        endOccupancy(agentRequest, runRegistry);
    }

    public static void endOccupancy(AgentExecutionCommand agentRequest, ActiveAgentRunRegistry runRegistry) {
        if (runRegistry == null || agentRequest == null || StringUtils.isBlank(agentRequest.getRequestId())) {
            return;
        }
        runRegistry.end(agentRequest.getRequestId());
    }
}
