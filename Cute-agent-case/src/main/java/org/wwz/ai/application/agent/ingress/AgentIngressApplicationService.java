package org.wwz.ai.application.agent.ingress;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.query.AgentQuerySubmitResult;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.query.SessionPreferences;
import org.wwz.ai.application.agent.run.AgentRunInjectApplicationService;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent 入站薄应用服务：校验、协议识别、幂等判断、路由和结果映射。
 * 真正的执行仍由现有 query / inject 应用服务负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentIngressApplicationService implements IAgentIngressApplicationService {

    private final IGptQueryApplicationService gptQueryApplicationService;
    private final AgentRunInjectApplicationService agentRunInjectApplicationService;
    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    private final ConversationSessionAuthorizationService conversationSessionAuthorizationService;

    @Override
    public AgentIngressResult submit(AgentIngressCommand command) {
        AgentIngressRoutingPolicy.validate(command);
        log.info("agent.ingress.received source={} type={} origin={} protocolVersion={} sessionId={} requestId={}",
                command.getSource(),
                command.getType(),
                command.getCommandOrigin(),
                command.getProtocolVersion(),
                command.getSessionId(),
                command.getRequestId());
        return command.getType() == AgentIngressType.USER_GUIDANCE
                ? submitGuidance(command)
                : submitNewTurn(command);
    }

    private AgentIngressResult submitNewTurn(AgentIngressCommand command) {
        Optional<ActiveAgentRunRegistry.ActiveRun> duplicate = activeAgentRunRegistry.find(command.getRequestId());
        if (duplicate.isPresent()) {
            return duplicateResult(command, duplicate.get());
        }

        try {
            AgentQuerySubmitResult submitted = command.getCommandOrigin() == AgentIngressOrigin.SESSION_QUERY
                    ? submitSessionQuery(command)
                    : submitMainChat(command);
            AgentIngressResult result = AgentIngressResult.builder()
                    .status(AgentIngressStatus.NEW_RUN_ACCEPTED)
                    .mode(AgentIngressMode.NEW_RUN)
                    .sessionId(submitted.getSessionId())
                    .requestId(submitted.getRequestId())
                    .accepted(submitted.isAccepted())
                    .build();
            logRouted(command, result);
            return result;
        } catch (AgentConcurrentRunException exception) {
            if (!AgentIngressRoutingPolicy.canConvertConcurrent(command, exception)) {
                throw exception;
            }
            AgentIngressResult result = convertToGuidance(command, exception.getActiveRequestId());
            logRouted(command, result);
            return result;
        }
    }

    private AgentQuerySubmitResult submitMainChat(AgentIngressCommand command) {
        GptQueryCommand queryCommand = command.getGptQueryCommand();
        if (queryCommand == null) {
            throw new IllegalArgumentException("gptQueryCommand 不能为空");
        }
        return gptQueryApplicationService.submitAgentQuery(queryCommand);
    }

    private AgentQuerySubmitResult submitSessionQuery(AgentIngressCommand command) {
        SessionPreferences preferences = command.getPreferences() == null
                ? SessionPreferences.none()
                : command.getPreferences();
        return gptQueryApplicationService.submitSessionQuery(
                command.getSessionId(),
                command.getQuery(),
                preferences,
                command.getRequestId());
    }

    private AgentIngressResult duplicateResult(AgentIngressCommand command,
                                               ActiveAgentRunRegistry.ActiveRun activeRun) {
        if (!StringUtils.equals(command.getSessionId(), activeRun.getSessionId())) {
            throw new AgentConcurrentRunException(
                    "requestId 已在其他会话使用",
                    activeRun.getRequestId(),
                    activeRun.getSessionId());
        }
        ensureSessionAccessible(command);
        AgentIngressResult result = AgentIngressResult.builder()
                .status(AgentIngressStatus.DUPLICATE)
                .mode(AgentIngressMode.NEW_RUN)
                .sessionId(command.getSessionId())
                .requestId(command.getRequestId())
                .activeRequestId(activeRun.getRequestId())
                .accepted(true)
                .duplicate(true)
                .message("该请求已接受，请继续观察已有任务")
                .build();
        logRouted(command, result);
        return result;
    }

    private AgentIngressResult convertToGuidance(AgentIngressCommand command, String activeRequestId) {
        String text = resolveQueryText(command);
        Map<String, Object> injected = agentRunInjectApplicationService.inject(
                command.getSessionId(),
                activeRequestId,
                text,
                command.getRequestId());
        AgentIngressResult result = mapInjectionResult(
                command.getSessionId(),
                command.getRequestId(),
                activeRequestId,
                injected);
        if (result.getStatus() != AgentIngressStatus.INJECT_FAILED) {
            return result;
        }
        return AgentIngressResult.builder()
                .status(AgentIngressStatus.INJECT_FAILED)
                .mode(AgentIngressMode.INJECT_FAILED)
                .sessionId(command.getSessionId())
                .requestId(command.getRequestId())
                .activeRequestId(activeRequestId)
                .accepted(false)
                .retryable(true)
                .message(result.getMessage())
                .build();
    }

    private AgentIngressResult submitGuidance(AgentIngressCommand command) {
        ensureSessionAccessible(command);
        Map<String, Object> injected = agentRunInjectApplicationService.inject(
                command.getSessionId(),
                command.getRequestId(),
                command.getQuery(),
                StringUtils.defaultIfBlank(command.getIdempotencyKey(), command.getClientMessageId()));
        AgentIngressResult result = mapInjectionResult(
                command.getSessionId(),
                command.getRequestId(),
                null,
                injected);
        logRouted(command, result);
        return result;
    }

    private void ensureSessionAccessible(AgentIngressCommand command) {
        String userId = StringUtils.defaultIfBlank(command.getUserId(), UserRequestContext.currentUserId());
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                userId,
                command.getSessionId());
    }

    private static AgentIngressResult mapInjectionResult(String sessionId,
                                                         String requestId,
                                                         String activeRequestId,
                                                         Map<String, Object> data) {
        boolean accepted = Boolean.TRUE.equals(data.get("accepted"));
        String rawMode = Objects.toString(data.get("mode"), "");
        AgentIngressMode mode = resolveMode(accepted, rawMode, data);
        AgentIngressStatus status = switch (mode) {
            case INJECTED -> AgentIngressStatus.INJECTED;
            case QUEUED -> AgentIngressStatus.QUEUED;
            case HELD -> AgentIngressStatus.HELD;
            case INJECT_FAILED -> AgentIngressStatus.INJECT_FAILED;
            case NEW_RUN -> AgentIngressStatus.INJECT_FAILED;
        };
        return AgentIngressResult.builder()
                .status(status)
                .mode(mode)
                .sessionId(sessionId)
                .requestId(requestId)
                .clientMessageId(Objects.toString(data.get("clientMessageId"), null))
                .activeRequestId(activeRequestId)
                .accepted(accepted)
                .queued(Boolean.TRUE.equals(data.get("queued")))
                .held(Boolean.TRUE.equals(data.get("held")))
                .wakeScheduled(Boolean.TRUE.equals(data.get("wakeScheduled")))
                .retryable(false)
                .duplicate(false)
                .message(Objects.toString(data.get("message"), null))
                .build();
    }

    private static AgentIngressMode resolveMode(boolean accepted,
                                                String rawMode,
                                                Map<String, Object> data) {
        if ("held".equals(rawMode) || Boolean.TRUE.equals(data.get("held"))) {
            return AgentIngressMode.HELD;
        }
        if (!accepted) {
            return "new_run".equals(rawMode)
                    ? AgentIngressMode.NEW_RUN
                    : AgentIngressMode.INJECT_FAILED;
        }
        if ("injected".equals(rawMode)) {
            return AgentIngressMode.INJECTED;
        }
        if ("queued".equals(rawMode)) {
            return AgentIngressMode.QUEUED;
        }
        if ("injected".equals(rawMode) || Boolean.FALSE.equals(data.get("queued"))) {
            return AgentIngressMode.INJECTED;
        }
        return AgentIngressMode.QUEUED;
    }

    private static String resolveQueryText(AgentIngressCommand command) {
        if (StringUtils.isNotBlank(command.getQuery())) {
            return command.getQuery();
        }
        return command.getGptQueryCommand() == null ? null : command.getGptQueryCommand().getQuery();
    }

    private static void logRouted(AgentIngressCommand command, AgentIngressResult result) {
        log.info("agent.ingress.routed source={} type={} origin={} status={} mode={} accepted={} queued={} held={} duplicate={} retryable={} requestId={} activeRequestId={}",
                command.getSource(),
                command.getType(),
                command.getCommandOrigin(),
                result.getStatus(),
                result.getMode(),
                result.isAccepted(),
                result.isQueued(),
                result.isHeld(),
                result.isDuplicate(),
                result.isRetryable(),
                result.getRequestId(),
                result.getActiveRequestId());
    }
}
