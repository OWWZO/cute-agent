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
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BackgroundTaskAutoContinuationSettlementTest {

    @After
    public void tearDown() {
        SessionBackgroundTaskHub.clearAll();
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

    @Test
    public void wakeLaunchFailureRestoresWaitingPhase() {
        String childSessionId = "sub_agent-waiting";
        String rootSessionId = "root-session";
        RuntimeBackgroundTask logicalTask = RuntimeBackgroundTask.builder()
                .id("task-waiting")
                .type(RuntimeBackgroundTask.TYPE_LOCAL_AGENT)
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .agentId("agent-waiting")
                .childSessionId(childSessionId)
                .parentSessionId("parent-session")
                .parentAgentId("root-session")
                .ownerAgentId("root-session")
                .rootSessionId(rootSessionId)
                .phase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND)
                .build();
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.querySessionEntity(childSessionId))
                .thenReturn(DialogueSession.builder()
                        .sessionId(childSessionId)
                        .userId("user-1")
                        .sessionKind(org.wwz.ai.domain.agent.ledger.model.SessionKind.SUBAGENT.code())
                        .build());
        TasklistPersistencePort persistence = Mockito.mock(TasklistPersistencePort.class);
        Mockito.when(persistence.findActiveBackgroundTaskByChildSessionId(childSessionId))
                .thenReturn(Optional.of(logicalTask));
        Mockito.when(persistence.loadBackgroundTasks(rootSessionId)).thenReturn(List.of(logicalTask));

        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        BackgroundTaskAutoContinuationApplicationService continuation =
                new BackgroundTaskAutoContinuationApplicationService(
                        readRepository,
                        (request, stream) -> {
                        },
                        activeRuns,
                        (ignoredSessionId, ignoredFrame) -> {
                        },
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
        ReflectionTestUtils.setField(continuation, "tasklistPersistencePort", persistence);

        Assert.assertTrue(continuation.dispatchWake(
                childSessionId, logicalTask.getId(), "background_task", null, ignored -> {
                }));
        Assert.assertEquals(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND, logicalTask.getPhase());
        Assert.assertEquals(Integer.valueOf(0), logicalTask.getClosingTurnCount());
        Assert.assertTrue(activeRuns.findBySessionId(childSessionId).isEmpty());
    }

    @Test
    public void completedSubAgentWithoutActiveLogicalTaskCanWakeAsNewLocalRoot() {
        String childSessionId = "sub_agent-direct-chat";
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.querySessionEntity(childSessionId))
                .thenReturn(DialogueSession.builder()
                        .sessionId(childSessionId)
                        .userId("user-1")
                        .sessionKind(org.wwz.ai.domain.agent.ledger.model.SessionKind.SUBAGENT.code())
                        .build());
        TasklistPersistencePort persistence = Mockito.mock(TasklistPersistencePort.class);
        Mockito.when(persistence.findActiveBackgroundTaskByChildSessionId(childSessionId))
                .thenReturn(Optional.empty());

        AtomicReference<AgentExecutionCommand> dispatched = new AtomicReference<>();
        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        BackgroundTaskAutoContinuationApplicationService continuation =
                new BackgroundTaskAutoContinuationApplicationService(
                        readRepository,
                        (request, stream) -> dispatched.set(request),
                        activeRuns,
                        (ignoredSessionId, ignoredFrame) -> {
                        },
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
        ReflectionTestUtils.setField(continuation, "tasklistPersistencePort", persistence);

        Assert.assertTrue(continuation.dispatchWake(
                childSessionId, null, "background_task", null, ignored -> {
                }));
        Assert.assertNotNull(dispatched.get());
        Assert.assertEquals("", dispatched.get().getQuery());
        Assert.assertEquals("", dispatched.get().getBasePrompt());
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
