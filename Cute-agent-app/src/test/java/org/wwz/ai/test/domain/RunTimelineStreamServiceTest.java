package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.query.RunTimelineQueryService;
import org.wwz.ai.application.agent.query.RunTimelineSnapshot;
import org.wwz.ai.application.agent.run.AgentRunFollowApplicationService;
import org.wwz.ai.application.agent.run.FollowAttachResult;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.trigger.http.agent.RunTimelineStreamService;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public class RunTimelineStreamServiceTest {

    @Test
    public void mustSubscribeBeforeSnapshotAndKeepLiveSubscriptionForAttachedRun()
            throws Exception {
        RunTimelineQueryService queryService = Mockito.mock(RunTimelineQueryService.class);
        AgentRunFollowApplicationService followService =
                Mockito.mock(AgentRunFollowApplicationService.class);
        AgentSessionEventBus eventBus = Mockito.mock(AgentSessionEventBus.class);
        AutoCloseable subscription = Mockito.mock(AutoCloseable.class);
        AtomicReference<Consumer<AgentSessionStreamFrame>> subscriber = new AtomicReference<>();

        Mockito.when(eventBus.subscribe(eq("session-1"), any(), any()))
                .thenAnswer(invocation -> {
                    subscriber.set(invocation.getArgument(1));
                    return subscription;
                });
        Mockito.when(followService.attachSession(eq("session-1"), eq(0L), Mockito.isNull()))
                .thenReturn(FollowAttachResult.ATTACHED);
        RunTimelineSnapshot running =
                new RunTimelineSnapshot("req-1", "session-1", "RUNNING", false, 0L, List.of());
        Mockito.when(queryService.query("user-1", "req-1", 0L)).thenReturn(running);

        new RunTimelineStreamService(queryService, followService, eventBus)
                .open("user-1", "req-1", running, 0L);

        InOrder order = Mockito.inOrder(eventBus, queryService);
        order.verify(eventBus).subscribe(eq("session-1"), any(), any());
        order.verify(queryService).query("user-1", "req-1", 0L);
        Assert.assertNotNull(subscriber.get());

        subscriber.get().accept(AgentSessionStreamFrame.builder()
                .eventSeq(7L)
                .runId("req-1")
                .itemId("tool:call-1")
                .eventType("item.completed")
                .build());
    }

    @Test
    public void wakeEventMustNotBeTreatedAsRunTerminal() throws Exception {
        RunTimelineQueryService queryService = Mockito.mock(RunTimelineQueryService.class);
        AgentRunFollowApplicationService followService =
                Mockito.mock(AgentRunFollowApplicationService.class);
        AgentSessionEventBus eventBus = Mockito.mock(AgentSessionEventBus.class);
        AtomicReference<Consumer<AgentSessionStreamFrame>> subscriber = new AtomicReference<>();

        Mockito.when(eventBus.subscribe(eq("session-1"), any(), any()))
                .thenAnswer(invocation -> {
                    subscriber.set(invocation.getArgument(1));
                    return Mockito.mock(AutoCloseable.class);
                });
        Mockito.when(followService.attachSession(eq("session-1"), eq(0L), Mockito.isNull()))
                .thenReturn(FollowAttachResult.ATTACHED);
        RunTimelineSnapshot running =
                new RunTimelineSnapshot("req-1", "session-1", "RUNNING", false, 0L, List.of());
        Mockito.when(queryService.query("user-1", "req-1", 0L)).thenReturn(running);

        new RunTimelineStreamService(queryService, followService, eventBus)
                .open("user-1", "req-1", running, 0L);
        Mockito.clearInvocations(queryService);

        subscriber.get().accept(AgentSessionStreamFrame.builder()
                .eventSeq(7L)
                .runId("req-1")
                .eventType("run.wake")
                .build());

        Mockito.verifyNoInteractions(queryService);
    }
}
