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
import org.wwz.ai.application.agent.run.AgentRunInjectApplicationService;
import org.wwz.ai.application.agent.run.AgentRunStopApplicationService;
import org.wwz.ai.trigger.http.agent.AgentRunController;
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
    private AgentRunInjectApplicationService injectService;
    private ConversationSessionAuthorizationService authorizationService;

    @Before
    public void setUp() {
        UserRequestContext.bind("user-test");
        controller = new AgentRunController();
        stopService = Mockito.mock(AgentRunStopApplicationService.class);
        injectService = Mockito.mock(AgentRunInjectApplicationService.class);
        authorizationService = Mockito.mock(ConversationSessionAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "agentRunStopApplicationService", stopService);
        ReflectionTestUtils.setField(controller, "agentRunInjectApplicationService", injectService);
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
        Mockito.verifyNoInteractions(stopService, authorizationService);
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
        Mockito.doThrow(new SessionOwnershipDeniedException("当前用户无权访问该会话"))
                .when(authorizationService).ensureExistingSessionAccessible("user-test", "session-1");

        Response<Map<String, Object>> response = controller.inject(req);

        Assert.assertEquals(ResponseCode.FORBIDDEN.getCode(), response.getCode());
        Mockito.verifyNoInteractions(injectService);
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
