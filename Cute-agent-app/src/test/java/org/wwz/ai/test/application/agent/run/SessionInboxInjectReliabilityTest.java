package org.wwz.ai.test.application.agent.run;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.run.AgentRunInjectApplicationService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.run.AgentRunStopApplicationService;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SessionInboxInjectReliabilityTest {

    @Test
    public void injectionDuringFinalReplyPersistsBeforeSchedulingWake() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        registry.begin("req-final", "sess-final", "user-1");
        AgentContext context = AgentContext.builder()
                .requestId("req-final")
                .sessionId("sess-final")
                .build();
        context.markTurnClosed();
        registry.bindContext("req-final", context);

        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        BackgroundTaskWakePort wakePort = Mockito.mock(BackgroundTaskWakePort.class);
        when(inbox.offer(any())).thenAnswer(invocation -> {
            SessionInboxItem offered = invocation.getArgument(0);
            return SessionInboxItem.builder()
                    .id(42L)
                    .sessionId(offered.getSessionId())
                    .kind(offered.getKind())
                    .visibility(offered.getVisibility())
                    .state(offered.getState())
                    .idempotencyKey(offered.getIdempotencyKey())
                    .payloadJson(offered.getPayloadJson())
                    .build();
        });

        AgentRunInjectApplicationService service =
                new AgentRunInjectApplicationService(registry);
        ReflectionTestUtils.setField(service, "sessionInboxPort", inbox);
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort", wakePort);

        Map<String, Object> result = service.inject(
                "sess-final", "req-final", "补充一个限制", "client-final-1");

        ArgumentCaptor<SessionInboxItem> item = ArgumentCaptor.forClass(SessionInboxItem.class);
        verify(inbox).offer(item.capture());
        Assert.assertEquals("client-final-1", item.getValue().getIdempotencyKey());
        Assert.assertEquals(SessionInboxItem.KIND_USER_MESSAGE, item.getValue().getKind());
        Assert.assertEquals(SessionInboxItem.VISIBILITY_ALL, item.getValue().getVisibility());
        InOrder order = Mockito.inOrder(inbox, wakePort);
        order.verify(inbox).offer(any());
        order.verify(wakePort).onInboxChanged("sess-final");
        Assert.assertEquals(true, result.get("accepted"));
        Assert.assertEquals(true, result.get("queued"));
    }

    @Test
    public void injectionForMissingRunPersistsAndWakesInsteadOfReturningNewRun() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        BackgroundTaskWakePort wakePort = Mockito.mock(BackgroundTaskWakePort.class);
        when(inbox.offer(any())).thenAnswer(invocation -> {
            SessionInboxItem offered = invocation.getArgument(0);
            return SessionInboxItem.builder()
                    .id(7L)
                    .state(offered.getState())
                    .kind(offered.getKind())
                    .idempotencyKey(offered.getIdempotencyKey())
                    .build();
        });

        AgentRunInjectApplicationService service =
                new AgentRunInjectApplicationService(registry);
        ReflectionTestUtils.setField(service, "sessionInboxPort", inbox);
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort", wakePort);

        Map<String, Object> result = service.inject("sess-idle", "req-gone", "继续", "client-idle-1");

        verify(inbox).offer(any());
        verify(wakePort).onInboxChanged("sess-idle");
        Assert.assertEquals(true, result.get("accepted"));
        Assert.assertNull(result.get("mode"));
    }

    @Test
    public void injectionForLiveRunStillPublishesWakeCheckAfterPersisting() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        registry.begin("req-live", "sess-live", "user-1");
        AgentContext context = AgentContext.builder()
                .requestId("req-live")
                .sessionId("sess-live")
                .build();
        registry.bindContext("req-live", context);

        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        BackgroundTaskWakePort wakePort = Mockito.mock(BackgroundTaskWakePort.class);
        when(inbox.offer(any())).thenAnswer(invocation -> {
            SessionInboxItem offered = invocation.getArgument(0);
            return SessionInboxItem.builder()
                    .id(9L)
                    .state(offered.getState())
                    .kind(offered.getKind())
                    .idempotencyKey(offered.getIdempotencyKey())
                    .build();
        });

        AgentRunInjectApplicationService service =
                new AgentRunInjectApplicationService(registry);
        ReflectionTestUtils.setField(service, "sessionInboxPort", inbox);
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort", wakePort);

        Map<String, Object> result = service.inject("sess-live", "req-live", "下一步按这个来", "client-live-1");

        verify(inbox).offer(any());
        verify(wakePort).onInboxChanged("sess-live");
        Assert.assertEquals(true, result.get("accepted"));
    }

    @Test
    public void activeRunEndingDuringOfferCannotSwallowTheInboxWake() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        registry.begin("req-race", "sess-race", "user-1");
        AgentContext context = AgentContext.builder()
                .requestId("req-race")
                .sessionId("sess-race")
                .build();
        registry.bindContext("req-race", context);

        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        BackgroundTaskWakePort wakePort = Mockito.mock(BackgroundTaskWakePort.class);
        when(inbox.offer(any())).thenAnswer(invocation -> {
            // 模拟“判活时还活着，入队提交的同一瞬间 run 已 end”的极端交错。
            registry.end("req-race");
            SessionInboxItem offered = invocation.getArgument(0);
            return SessionInboxItem.builder()
                    .id(11L)
                    .state(offered.getState())
                    .kind(offered.getKind())
                    .idempotencyKey(offered.getIdempotencyKey())
                    .build();
        });

        AgentRunInjectApplicationService service =
                new AgentRunInjectApplicationService(registry);
        ReflectionTestUtils.setField(service, "sessionInboxPort", inbox);
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort", wakePort);

        Map<String, Object> result = service.inject(
                "sess-race", "req-race", "继续", "client-race-1");

        InOrder order = Mockito.inOrder(inbox, wakePort);
        order.verify(inbox).offer(any());
        order.verify(wakePort).onInboxChanged("sess-race");
        Assert.assertEquals("已排队，将自动继续处理", result.get("message"));
    }

    @Test
    public void stopHoldsPersistedUserMessages() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        registry.begin("req-stop", "sess-stop", "user-1");
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);

        AgentRunStopApplicationService service =
                new AgentRunStopApplicationService(registry, new AgentRunLaunchGate(0));
        ReflectionTestUtils.setField(service, "sessionInboxPort", inbox);

        service.stop("sess-stop", "req-stop");

        verify(inbox).holdPendingUserMessages("sess-stop");
    }
}
