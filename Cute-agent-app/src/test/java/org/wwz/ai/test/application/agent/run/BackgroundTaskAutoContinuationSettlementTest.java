package org.wwz.ai.test.application.agent.run;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.run.BackgroundTaskAutoContinuationApplicationService;
import org.wwz.ai.application.agent.run.BackgroundTaskWakeAdapter;
import org.wwz.ai.application.agent.run.SessionYieldGate;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentTimelineFrameEnricher;
import org.wwz.ai.application.agent.stream.SessionEventClock;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BackgroundTaskAutoContinuationSettlementTest {

    @After
    public void tearDown() {
        SessionNotificationHub.clearAll();
    }

    @Test
    public void wakeRunShouldScheduleAgainWhenAnotherNotificationArrivesBeforeItSettles() throws Exception {
        String sessionId = "session-wake-settlement";
        SessionNotificationHub.offer(sessionId, notification("task-a"));

        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        AgentSessionEventBus eventBus = (ignoredSessionId, ignoredFrame) -> {
        };
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.querySessionEntity(sessionId))
                .thenReturn(DialogueSession.builder().sessionId(sessionId).userId("user-1").build());

        AtomicInteger dispatchCount = new AtomicInteger();
        CountDownLatch dispatches = new CountDownLatch(2);
        AtomicReference<BackgroundTaskWakeAdapter> adapterRef = new AtomicReference<>();
        IAgentDispatchService dispatchService = (request, stream) -> {
            int dispatchNo = dispatchCount.incrementAndGet();
            if (dispatchNo == 1) {
                SessionNotificationHub.offer(sessionId, notification("task-b"));
                adapterRef.get().onBackgroundTaskCompleted(event(sessionId, "task-b"));
            } else {
                SessionNotificationHub.drain(sessionId);
            }
            dispatches.countDown();
        };

        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        config.setBackgroundAutoContinuationDelayMs(0L);

        BackgroundTaskAutoContinuationApplicationService continuation =
                new BackgroundTaskAutoContinuationApplicationService(
                        readRepository,
                        dispatchService,
                        activeRuns,
                        eventBus,
                        new AgentRunLaunchGate(0),
                        new SessionEventClock(
                                readRepository,
                                Mockito.mock(IExecutionLedgerWriteRepository.class)),
                        projections,
                        config);
        ReflectionTestUtils.setField(continuation, "handlerMap", Collections.emptyMap());
        ReflectionTestUtils.setField(continuation, "dispatchExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(continuation, "agentTimelineFrameEnricher",
                Mockito.mock(AgentTimelineFrameEnricher.class));

        BackgroundTaskWakeAdapter adapter = new BackgroundTaskWakeAdapter(
                activeRuns,
                new SessionYieldGate() {
                    @Override
                    public boolean hasOpenYield(String ignoredSessionId) {
                        return false;
                    }
                },
                continuation,
                projections,
                config);
        adapterRef.set(adapter);

        try {
            Assert.assertTrue(adapter.onBackgroundTaskCompleted(event(sessionId, "task-a")));
            Assert.assertTrue("唤醒轮结束前到达的新通知必须再触发一次唤醒",
                    dispatches.await(3, TimeUnit.SECONDS));
            Assert.assertFalse(SessionNotificationHub.hasPending(sessionId));
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    public void userInboxWakeKeepsModelQueryEmptyButPersistsDisplayQueryInLedger() {
        String sessionId = "session-user-inbox-ledger";
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        AgentSessionEventBus eventBus = (ignoredSessionId, ignoredFrame) -> {
        };
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.querySessionEntity(sessionId))
                .thenReturn(DialogueSession.builder().sessionId(sessionId).userId("user-1").build());

        AtomicReference<AgentExecutionCommand> dispatched = new AtomicReference<>();
        IAgentDispatchService dispatchService = (request, stream) -> dispatched.set(request);
        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        config.setBackgroundAutoContinuationDelayMs(0L);
        BackgroundTaskAutoContinuationApplicationService continuation =
                new BackgroundTaskAutoContinuationApplicationService(
                        readRepository,
                        dispatchService,
                        activeRuns,
                        eventBus,
                        new AgentRunLaunchGate(0),
                        new SessionEventClock(
                                readRepository,
                                Mockito.mock(IExecutionLedgerWriteRepository.class)),
                        projections,
                        config);
        ReflectionTestUtils.setField(continuation, "handlerMap", Collections.emptyMap());
        ReflectionTestUtils.setField(continuation, "dispatchExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(continuation, "agentTimelineFrameEnricher",
                Mockito.mock(AgentTimelineFrameEnricher.class));

        Assert.assertTrue(continuation.dispatchWake(
                sessionId, null, "user_inbox", "继续处理", ignored -> {
                }));
        Assert.assertNotNull(dispatched.get());
        Assert.assertEquals("", dispatched.get().getQuery());
        Assert.assertEquals("继续处理", dispatched.get().getLedgerQueryText());
    }

    @Test
    public void busyWakeDoesNotImmediatelyResettleIntoAnotherWake() {
        String sessionId = "session-wake-busy";
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        AgentSessionEventBus eventBus = (ignoredSessionId, ignoredFrame) -> {
        };
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.querySessionEntity(sessionId))
                .thenReturn(DialogueSession.builder().sessionId(sessionId).userId("user-1").build());

        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        BackgroundTaskAutoContinuationApplicationService continuation =
                new BackgroundTaskAutoContinuationApplicationService(
                        readRepository,
                        (request, stream) -> {
                        },
                        activeRuns,
                        eventBus,
                        new AgentRunLaunchGate(0),
                        new SessionEventClock(
                                readRepository,
                                Mockito.mock(IExecutionLedgerWriteRepository.class)),
                        projections,
                        config);
        ReflectionTestUtils.setField(continuation, "handlerMap", Collections.emptyMap());
        ReflectionTestUtils.setField(continuation, "dispatchExecutor", (Executor) command -> {
            throw new RejectedExecutionException("busy");
        });
        ReflectionTestUtils.setField(continuation, "agentTimelineFrameEnricher",
                Mockito.mock(AgentTimelineFrameEnricher.class));
        AtomicInteger settleCount = new AtomicInteger();

        boolean accepted = continuation.dispatchWake(
                sessionId, null, "background_task", null, requestId -> settleCount.incrementAndGet());

        Assert.assertTrue(accepted);
        Assert.assertEquals(0, settleCount.get());
        Assert.assertTrue(activeRuns.findBySessionId(sessionId).isEmpty());
    }

    private static BackgroundTaskCompletionEvent event(String sessionId, String taskId) {
        return new BackgroundTaskCompletionEvent(
                sessionId, "request-1", taskId, "agent-1", "child-1",
                RuntimeBackgroundTask.STATUS_COMPLETED, "desc");
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(
                taskId,
                "agent-" + taskId,
                "child-" + taskId,
                "general-purpose",
                "desc",
                RuntimeBackgroundTask.STATUS_COMPLETED,
                "ok",
                null,
                1,
                10L,
                null,
                0L);
    }
}
