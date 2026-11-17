package org.wwz.ai.test.domain;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.application.agent.ingress.AgentIngressCommand;
import org.wwz.ai.application.agent.ingress.AgentIngressMode;
import org.wwz.ai.application.agent.ingress.AgentIngressResult;
import org.wwz.ai.application.agent.ingress.AgentIngressStatus;
import org.wwz.ai.application.agent.ingress.IAgentIngressApplicationService;
import org.wwz.ai.application.agent.run.AgentRunStopApplicationService;
import org.wwz.ai.trigger.http.agent.AgentRunController;
import org.wwz.ai.trigger.http.agent.mapper.AgentIngressHttpMapper;
import org.wwz.ai.trigger.http.agent.vo.AgentRunInjectReqVO;
import org.wwz.ai.trigger.http.agent.vo.AgentRunStopReqVO;
import org.wwz.ai.types.agent.user.UserRequestContext;
import org.wwz.ai.types.enums.ResponseCode;

import java.util.Map;

/**
 * run 控制入口的归属校验：注入 / 停止不能越过会话授权。
 */
public class AgentRunControllerTest {

    private AgentRunController controller;
    private AgentRunStopApplicationService stopService;
    private IAgentIngressApplicationService ingressService;
    private ConversationSessionAuthorizationService authorizationService;

    @Before
    public void setUp() {
        UserRequestContext.bind("user-test");
        controller = new AgentRunController();
        stopService = Mockito.mock(AgentRunStopApplicationService.class);
        ingressService = Mockito.mock(IAgentIngressApplicationService.class);
        authorizationService = Mockito.mock(ConversationSessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "agentRunStopApplicationService", stopService);
        ReflectionTestUtils.setField(controller, "agentIngressApplicationService", ingressService);
        ReflectionTestUtils.setField(controller, "agentIngressHttpMapper", new AgentIngressHttpMapper());
        ReflectionTestUtils.setField(controller, "conversationSessionAuthorizationService", authorizationService);
    }

    @After
    public void tearDown() {
        UserRequestContext.clear();
    }

    @Test
    public void shouldRejectStopWithoutSessionId() {
        AgentRunStopReqVO req = new AgentRunStopReqVO();
        req.setRequestId("req-1");

        Response<Map<String, Object>> response = controller.stop(req);

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        Mockito.verifyNoInteractions(stopService);
    }

    @Test
    public void shouldRejectStopForForeignSession() {
        AgentRunStopReqVO req = new AgentRunStopReqVO();
        req.setRequestId("req-1");
        req.setSessionId("session-1");
        Mockito.doThrow(new SessionOwnershipDeniedException("当前用户无权访问该会话"))
                .when(authorizationService).ensureExistingSessionAccessible("user-test", "session-1");

        Response<Map<String, Object>> response = controller.stop(req);

        Assert.assertEquals(ResponseCode.FORBIDDEN.getCode(), response.getCode());
        Assert.assertEquals("当前用户无权访问该会话", response.getInfo());
        Mockito.verifyNoInteractions(stopService);
    }

    @Test
    public void shouldRejectInjectForForeignSession() {
        AgentRunInjectReqVO req = new AgentRunInjectReqVO();
        req.setRequestId("req-1");
        req.setSessionId("session-1");
        req.setText("补充一点");
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenThrow(new SessionOwnershipDeniedException("当前用户无权访问该会话"));

        Response<Map<String, Object>> response = controller.inject(req, null);

        Assert.assertEquals(ResponseCode.FORBIDDEN.getCode(), response.getCode());
    }

    @Test
    public void shouldInjectAccessibleSessionThroughIngress() {
        AgentRunInjectReqVO req = new AgentRunInjectReqVO();
        req.setRequestId("req-1");
        req.setSessionId("session-1");
        req.setText("补充一点");
        req.setClientMessageId("client-1");
        Mockito.when(ingressService.submit(Mockito.any(AgentIngressCommand.class)))
                .thenReturn(AgentIngressResult.builder()
                        .status(AgentIngressStatus.INJECTED)
                        .mode(AgentIngressMode.INJECTED)
                        .sessionId("session-1")
                        .requestId("req-1")
                        .clientMessageId("client-1")
                        .accepted(true)
                        .queued(true)
                        .message("已接受指导")
                        .build());

        Response<Map<String, Object>> response = controller.inject(req, "1");

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertEquals("injected", response.getData().get("mode"));
        Assert.assertEquals(Boolean.TRUE, response.getData().get("accepted"));
    }

    @Test
    public void shouldStopAccessibleSession() {
        AgentRunStopReqVO req = new AgentRunStopReqVO();
        req.setRequestId("req-1");
        req.setSessionId("session-1");
        Mockito.when(stopService.stop("session-1", "req-1")).thenReturn(Map.of("stopped", true));

        Response<Map<String, Object>> response = controller.stop(req);

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertEquals(Boolean.TRUE, response.getData().get("stopped"));
    }
}
