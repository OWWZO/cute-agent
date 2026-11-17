package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.ingress.AgentIngressCommand;
import org.wwz.ai.application.agent.ingress.AgentIngressMode;
import org.wwz.ai.application.agent.ingress.AgentIngressResult;
import org.wwz.ai.application.agent.ingress.AgentIngressStatus;
import org.wwz.ai.application.agent.ingress.IAgentIngressApplicationService;
import org.wwz.ai.trigger.http.AiAgentController;
import org.wwz.ai.trigger.http.agent.AgentSessionQueryController;
import org.wwz.ai.trigger.http.agent.mapper.AgentIngressHttpMapper;
import org.wwz.ai.trigger.http.agent.mapper.GptQueryRequestMapper;
import org.wwz.ai.trigger.http.agent.vo.AgentSessionQueryReqVO;
import org.wwz.ai.trigger.http.agent.vo.GptQueryRequestVO;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;

import java.util.Map;

public class AgentIngressHttpContractTest {

    private IAgentIngressApplicationService ingressService;
    private AiAgentController mainController;
    private AgentSessionQueryController sessionController;

    @Before
    public void setUp() {
        ingressService = Mockito.mock(IAgentIngressApplicationService.class);
        mainController = new AiAgentController();
        ReflectionTestUtils.setField(mainController, "agentIngressApplicationService", ingressService);
        ReflectionTestUtils.setField(mainController, "gptQueryRequestMapper", new GptQueryRequestMapper());
        ReflectionTestUtils.setField(mainController, "agentIngressHttpMapper", new AgentIngressHttpMapper());

        sessionController = new AgentSessionQueryController();
        ReflectionTestUtils.setField(sessionController, "agentIngressApplicationService", ingressService);
        ReflectionTestUtils.setField(sessionController, "agentIngressHttpMapper", new AgentIngressHttpMapper());
    }

    @Test
    public void legacyMainChatKeepsOriginalFieldsWithoutMode() {
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenReturn(newRunResult());

        ResponseEntity<Response<Map<String, Object>>> response =
                mainController.queryAgentStreamIncr(request(), null);

        Assert.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assert.assertEquals(true, response.getBody().getData().get("accepted"));
        Assert.assertFalse(response.getBody().getData().containsKey("mode"));
    }

    @Test
    public void newMainChatReceivesGuidanceMode() {
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenReturn(AgentIngressResult.builder()
                        .status(AgentIngressStatus.QUEUED)
                        .mode(AgentIngressMode.QUEUED)
                        .sessionId("sess-1")
                        .requestId("req-1")
                        .activeRequestId("active-1")
                        .accepted(true)
                        .queued(true)
                        .build());

        ResponseEntity<Response<Map<String, Object>>> response =
                mainController.queryAgentStreamIncr(request(), "1");

        Assert.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assert.assertEquals("queued", response.getBody().getData().get("mode"));
        Assert.assertEquals("active-1", response.getBody().getData().get("activeRequestId"));
    }

    @Test
    public void newMainChatInjectFailureReturns503() {
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenReturn(AgentIngressResult.builder()
                        .status(AgentIngressStatus.INJECT_FAILED)
                        .mode(AgentIngressMode.INJECT_FAILED)
                        .sessionId("sess-1")
                        .requestId("req-1")
                        .activeRequestId("active-1")
                        .accepted(false)
                        .retryable(true)
                        .message("队列不可用")
                        .build());

        ResponseEntity<Response<Map<String, Object>>> response =
                mainController.queryAgentStreamIncr(request(), "1");

        Assert.assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Assert.assertEquals("inject_failed", response.getBody().getData().get("mode"));
        Assert.assertEquals(true, response.getBody().getData().get("retryable"));
    }

    @Test
    public void sessionQueryUsesClientRequestIdAndReturnsMode() {
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenReturn(AgentIngressResult.builder()
                        .status(AgentIngressStatus.NEW_RUN_ACCEPTED)
                        .mode(AgentIngressMode.NEW_RUN)
                        .sessionId("sub-1")
                        .requestId("req-1")
                        .accepted(true)
                        .build());
        AgentSessionQueryReqVO body = AgentSessionQueryReqVO.builder()
                .query("继续")
                .requestId("req-1")
                .build();

        ResponseEntity<Response<Map<String, Object>>> response =
                sessionController.query("sub-1", body, "1");

        Assert.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assert.assertEquals("req-1", response.getBody().getData().get("requestId"));
        Assert.assertEquals("new_run", response.getBody().getData().get("mode"));
    }

    @Test
    public void sessionQueryConflictIncludesActiveSessionId() {
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenThrow(new AgentConcurrentRunException("busy", "active-1", "sub-1"));
        AgentSessionQueryReqVO body = AgentSessionQueryReqVO.builder()
                .query("继续")
                .requestId("req-1")
                .build();

        ResponseEntity<Response<Map<String, Object>>> response =
                sessionController.query("sub-1", body, "1");

        Assert.assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Assert.assertEquals("active-1", response.getBody().getData().get("activeRequestId"));
        Assert.assertEquals("sub-1", response.getBody().getData().get("activeSessionId"));
    }

    private static GptQueryRequestVO request() {
        return GptQueryRequestVO.builder()
                .sessionId("sess-1")
                .requestId("req-1")
                .query("hello")
                .build();
    }

    private static AgentIngressResult newRunResult() {
        return AgentIngressResult.builder()
                .status(AgentIngressStatus.NEW_RUN_ACCEPTED)
                .mode(AgentIngressMode.NEW_RUN)
                .sessionId("sess-1")
                .requestId("req-1")
                .accepted(true)
                .build();
    }
}
