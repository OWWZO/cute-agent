package org.wwz.ai.trigger.http.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import org.wwz.ai.application.agent.query.SessionPreferences;
import org.wwz.ai.trigger.http.agent.mapper.AgentIngressHttpMapper;
import org.wwz.ai.trigger.http.agent.vo.AgentSessionQueryReqVO;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话内发起新一轮 run（含子会话）。
 * <p>
 * 与 {@code POST /web/api/v1/gpt/queryAgentStreamIncr} 的区别：那个入口从请求体读模型/思考档位，
 * 本入口从**会话元数据**重建——子会话在父 Agent 结束后内存上下文已回收，
 * 只能靠库里存的偏好还原运行时选择。
 * </p>
 * <p>观察流仍走 {@code GET /api/agent/session/{sessionId}/stream}，与本接口无关。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/session")
public class AgentSessionQueryController {

    @Resource
    private IAgentIngressApplicationService agentIngressApplicationService;

    @Resource
    private AgentIngressHttpMapper agentIngressHttpMapper;

    @PostMapping("/{sessionId}/query")
    public ResponseEntity<Response<Map<String, Object>>> query(@PathVariable String sessionId,
                                                               @RequestBody AgentSessionQueryReqVO body,
                                                               @RequestHeader(value = AgentIngressProtocol.HEADER,
                                                                       required = false) String ingressVersion) {
        String query = body == null ? null : body.getQuery();
        try {
            if (StringUtils.isBlank(query)) {
                return ResponseEntity.badRequest().body(Response.<Map<String, Object>>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("query 不能为空")
                        .build());
            }
            Integer protocolVersion = agentIngressHttpMapper.protocolVersion(ingressVersion);
            AgentIngressResult result = agentIngressApplicationService.submit(AgentIngressCommand.builder()
                    .source(AgentIngressSource.WEB)
                    .type(AgentIngressType.NEW_TURN)
                    .commandOrigin(AgentIngressOrigin.SESSION_QUERY)
                    .protocolVersion(protocolVersion)
                    .sessionId(sessionId)
                    .requestId(body.getRequestId())
                    .query(query)
                    .preferences(SessionPreferences.builder()
                            .model(body.getModel())
                            .thinking(body.getThinking())
                            .thinkingEffort(body.getThinkingEffort())
                            .deepThink(body.getDeepThink())
                            .build())
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
        } catch (SessionNotInteractiveException | SessionOwnershipDeniedException e) {
            log.warn("session query denied sessionId={}: {}", sessionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.FORBIDDEN.getCode())
                    .info(e.getMessage())
                    .build());
        } catch (AgentConcurrentRunException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accepted", false);
            data.put("activeRequestId", e.getActiveRequestId());
            data.put("activeSessionId", e.getActiveSessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .data(data)
                    .build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("session query failed sessionId={}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.<Map<String, Object>>builder()
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
