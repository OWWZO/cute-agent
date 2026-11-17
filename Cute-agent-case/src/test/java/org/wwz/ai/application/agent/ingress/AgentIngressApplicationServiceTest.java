package org.wwz.ai.application.agent.ingress;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.query.AgentQuerySubmitResult;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.query.SessionPreferences;
import org.wwz.ai.application.agent.run.AgentRunInjectApplicationService;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;

import java.util.Map;

public class AgentIngressApplicationServiceTest {

    private IGptQueryApplicationService gptQueryApplicationService;
    private AgentRunInjectApplicationService injectApplicationService;
    private ActiveAgentRunRegistry activeAgentRunRegistry;
    private ConversationSessionAuthorizationService authorizationService;
    private AgentIngressApplicationService service;

    @Before
    public void setUp() {
        gptQueryApplicationService = Mockito.mock(IGptQueryApplicationService.class);
        injectApplicationService = Mockito.mock(AgentRunInjectApplicationService.class);
        activeAgentRunRegistry = new ActiveAgentRunRegistry();
        authorizationService = Mockito.mock(ConversationSessionAuthorizationService.class);
        service = new AgentIngressApplicationService(
                gptQueryApplicationService,
                injectApplicationService,
                activeAgentRunRegistry,
                authorizationService);
    }

    @Test
    public void newTurnDelegatesOnlyToQueryService() {
        Mockito.when(gptQueryApplicationService.submitAgentQuery(Mockito.any()))
                .thenReturn(AgentQuerySubmitResult.builder()
                        .accepted(true)
                        .sessionId("sess-1")
                        .requestId("req-1")
                        .build());

        AgentIngressResult result = service.submit(newTurnCommand("sess-1", "req-1", 1));

        Assert.assertEquals(AgentIngressStatus.NEW_RUN_ACCEPTED, result.getStatus());
        Assert.assertEquals(AgentIngressMode.NEW_RUN, result.getMode());
        Assert.assertTrue(result.isAccepted());
        Mockito.verifyNoInteractions(injectApplicationService);
    }

    @Test
    public void newProtocolConcurrentSameSessionBecomesInjectedGuidance() {
        Mockito.when(gptQueryApplicationService.submitAgentQuery(Mockito.any()))
                .thenThrow(new AgentConcurrentRunException("busy", "active-req", "sess-1"));
        Mockito.when(injectApplicationService.inject(
                        "sess-1", "active-req", "hello", "req-1"))
                .thenReturn(Map.of(
                        "accepted", true,
                        "mode", "injected",
                        "queued", true,
                        "clientMessageId", "req-1",
                        "message", "已接受指导"));

        AgentIngressResult result = service.submit(newTurnCommand("sess-1", "req-1", 1));

        Assert.assertEquals(AgentIngressStatus.INJECTED, result.getStatus());
        Assert.assertEquals(AgentIngressMode.INJECTED, result.getMode());
        Assert.assertEquals("active-req", result.getActiveRequestId());
        Assert.assertEquals("req-1", result.getClientMessageId());
    }

    @Test(expected = AgentConcurrentRunException.class)
    public void concurrentDifferentSessionIsNeverInjected() {
        Mockito.when(gptQueryApplicationService.submitAgentQuery(Mockito.any()))
                .thenThrow(new AgentConcurrentRunException("busy", "active-req", "sess-other"));

        service.submit(newTurnCommand("sess-1", "req-1", 1));

        Mockito.verifyNoInteractions(injectApplicationService);
    }

    @Test
    public void legacyClientKeepsConcurrentExceptionForController409Mapping() {
        Mockito.when(gptQueryApplicationService.submitAgentQuery(Mockito.any()))
                .thenThrow(new AgentConcurrentRunException("busy", "active-req", "sess-1"));

        try {
            service.submit(newTurnCommand("sess-1", "req-1", null));
            Assert.fail("旧客户端必须继续收到并发异常");
        } catch (AgentConcurrentRunException expected) {
            Assert.assertEquals("active-req", expected.getActiveRequestId());
        }
        Mockito.verifyNoInteractions(injectApplicationService);
    }

    @Test
    public void injectionFailureBecomesRetryable503Result() {
        Mockito.when(gptQueryApplicationService.submitAgentQuery(Mockito.any()))
                .thenThrow(new AgentConcurrentRunException("busy", "active-req", "sess-1"));
        Mockito.when(injectApplicationService.inject(
                        "sess-1", "active-req", "hello", "req-1"))
                .thenReturn(Map.of(
                        "accepted", false,
                        "message", "消息队列暂不可用"));

        AgentIngressResult result = service.submit(newTurnCommand("sess-1", "req-1", 1));

        Assert.assertEquals(AgentIngressStatus.INJECT_FAILED, result.getStatus());
        Assert.assertEquals(AgentIngressMode.INJECT_FAILED, result.getMode());
        Assert.assertTrue(result.isRetryable());
        Assert.assertFalse(result.isAccepted());
    }

    @Test(expected = IllegalArgumentException.class)
    public void newProtocolRequiresRequestId() {
        service.submit(newTurnCommand("sess-1", " ", 1));
    }

    @Test
    public void duplicateRequestIdReturnsAcceptedWithoutDispatchingAgain() {
        activeAgentRunRegistry.begin("req-1", "sess-1", "user-1");

        AgentIngressResult result = service.submit(newTurnCommand("sess-1", "req-1", 1));

        Assert.assertEquals(AgentIngressStatus.DUPLICATE, result.getStatus());
        Assert.assertTrue(result.isDuplicate());
        Assert.assertTrue(result.isAccepted());
        Mockito.verifyNoInteractions(gptQueryApplicationService, injectApplicationService);
    }

    @Test(expected = AgentConcurrentRunException.class)
    public void duplicateRequestIdCannotCrossSessions() {
        activeAgentRunRegistry.begin("req-1", "sess-other", "user-1");

        service.submit(newTurnCommand("sess-1", "req-1", 1));
    }

    @Test
    public void guidanceMapsHeldModeWithoutParsingMessageText() {
        Mockito.when(injectApplicationService.inject(
                        "sess-1", "active-req", "continue", "client-1"))
                .thenReturn(Map.of(
                        "accepted", true,
                        "queued", true,
                        "held", true,
                        "wakeScheduled", false,
                        "message", "消息已保留"));
        AgentIngressCommand command = AgentIngressCommand.builder()
                .source(AgentIngressSource.WEB)
                .type(AgentIngressType.USER_GUIDANCE)
                .commandOrigin(AgentIngressOrigin.RUN_INJECT)
                .userId("user-1")
                .sessionId("sess-1")
                .requestId("active-req")
                .clientMessageId("client-1")
                .query("continue")
                .build();

        AgentIngressResult result = service.submit(command);

        Assert.assertEquals(AgentIngressStatus.HELD, result.getStatus());
        Assert.assertEquals(AgentIngressMode.HELD, result.getMode());
        Assert.assertTrue(result.isHeld());
        Assert.assertFalse(result.isWakeScheduled());
    }

    @Test
    public void sessionQueryUsesClientRequestId() {
        Mockito.when(gptQueryApplicationService.submitSessionQuery(
                        Mockito.eq("sess-1"),
                        Mockito.eq("hello"),
                        Mockito.any(SessionPreferences.class),
                        Mockito.eq("req-1")))
                .thenReturn(AgentQuerySubmitResult.builder()
                        .accepted(true)
                        .sessionId("sess-1")
                        .requestId("req-1")
                        .build());
        AgentIngressCommand command = AgentIngressCommand.builder()
                .source(AgentIngressSource.WEB)
                .type(AgentIngressType.NEW_TURN)
                .commandOrigin(AgentIngressOrigin.SESSION_QUERY)
                .protocolVersion(1)
                .sessionId("sess-1")
                .requestId("req-1")
                .query("hello")
                .preferences(SessionPreferences.none())
                .build();

        AgentIngressResult result = service.submit(command);

        Assert.assertEquals(AgentIngressStatus.NEW_RUN_ACCEPTED, result.getStatus());
        Assert.assertEquals("req-1", result.getRequestId());
    }

    private static AgentIngressCommand newTurnCommand(String sessionId,
                                                      String requestId,
                                                      Integer protocolVersion) {
        return AgentIngressCommand.builder()
                .source(AgentIngressSource.WEB)
                .type(AgentIngressType.NEW_TURN)
                .commandOrigin(AgentIngressOrigin.MAIN_CHAT)
                .protocolVersion(protocolVersion)
                .userId("user-1")
                .sessionId(sessionId)
                .requestId(requestId)
                .query("hello")
                .gptQueryCommand(GptQueryCommand.builder()
                        .sessionId(sessionId)
                        .requestId(requestId)
                        .query("hello")
                        .build())
                .build();
    }
}
