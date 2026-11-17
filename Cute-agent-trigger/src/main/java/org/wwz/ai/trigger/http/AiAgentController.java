package org.wwz.ai.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.authorization.SessionNotInteractiveException;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.application.agent.ingress.AgentIngressCommand;
import org.wwz.ai.application.agent.ingress.AgentIngressMode;
import org.wwz.ai.application.agent.ingress.AgentIngressOrigin;
import org.wwz.ai.application.agent.ingress.AgentIngressProtocol;
import org.wwz.ai.application.agent.ingress.AgentIngressResult;
import org.wwz.ai.application.agent.ingress.AgentIngressSource;
import org.wwz.ai.application.agent.ingress.AgentIngressStatus;
import org.wwz.ai.application.agent.ingress.AgentIngressType;
import org.wwz.ai.application.agent.ingress.IAgentIngressApplicationService;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.trigger.http.agent.mapper.AgentIngressHttpMapper;
import org.wwz.ai.trigger.http.agent.mapper.GptQueryRequestMapper;
import org.wwz.ai.trigger.http.agent.vo.GptQueryRequestVO;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;
import org.wwz.ai.types.enums.ResponseCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Agent HTTP 入口：主对话提交与健康检查。
 */
@Slf4j
@RestController
@RequestMapping("/")
public class AiAgentController {

    @Resource
    private IAgentIngressApplicationService agentIngressApplicationService;

    @Resource
    private GptQueryRequestMapper gptQueryRequestMapper;

    @Resource
    private AgentIngressHttpMapper agentIngressHttpMapper;

    @RequestMapping(value = "/web/health", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /**
     * 提交一轮 Agent 执行。成功返回 JSON；观察流走 GET /api/agent/session/{sessionId}/stream。
     * 已有进行中的任务返回 409，不创建 SSE。
     */
    @RequestMapping(value = "/web/api/v1/gpt/queryAgentStreamIncr", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response<Map<String, Object>>> queryAgentStreamIncr(
            @RequestBody GptQueryRequestVO params,
            @RequestHeader(value = AgentIngressProtocol.HEADER, required = false) String ingressVersion) {
        String requestId = Objects.toString(params == null ? null : params.getRequestId(), "legacy-gpt-query");
        try {
            Integer protocolVersion = agentIngressHttpMapper.protocolVersion(ingressVersion);
            GptQueryCommand command = gptQueryRequestMapper.toCommand(params);
            AgentIngressResult result = agentIngressApplicationService.submit(AgentIngressCommand.builder()
                    .source(AgentIngressSource.WEB)
                    .type(AgentIngressType.NEW_TURN)
                    .commandOrigin(AgentIngressOrigin.MAIN_CHAT)
                    .protocolVersion(protocolVersion)
                    .sessionId(params == null ? null : params.getSessionId())
                    .requestId(params == null ? null : params.getRequestId())
                    .query(params == null ? null : params.getQuery())
                    .gptQueryCommand(command)
                    .build());
            if (result.getStatus() == AgentIngressStatus.INJECT_FAILED) {
                return injectFailedResponse(result);
            }
            Map<String, Object> data = agentIngressHttpMapper.data(
                    result,
                    agentIngressHttpMapper.isProtocolV1(protocolVersion));
            return ResponseEntity.ok(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build());
        } catch (AgentConcurrentRunException e) {
            log.warn("{} concurrent run rejected", requestId, e);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accepted", false);
            data.put("activeRequestId", e.getActiveRequestId());
            data.put("activeSessionId", e.getActiveSessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .data(data)
                    .build());
        } catch (AgentExecutorBusyException e) {
            log.warn("{} dispatch busy", requestId, e);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build());
        } catch (SessionNotInteractiveException | SessionOwnershipDeniedException e) {
            log.warn("{} session access denied: {}", requestId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.FORBIDDEN.getCode())
                    .info(e.getMessage())
                    .build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("{} reject gpt query", requestId, e);
            return ResponseEntity.badRequest().body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("{} queryAgentStreamIncr bootstrap error", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build());
        }
    }

    private static ResponseEntity<Response<Map<String, Object>>> injectFailedResponse(AgentIngressResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", false);
        data.put("mode", AgentIngressMode.INJECT_FAILED.value());
        data.put("activeRequestId", result.getActiveRequestId());
        data.put("retryable", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Response.<Map<String, Object>>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info(result.getMessage())
                        .data(data)
                        .build());
    }
}
