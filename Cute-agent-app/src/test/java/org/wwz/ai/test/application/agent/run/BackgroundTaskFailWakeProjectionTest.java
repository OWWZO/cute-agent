package org.wwz.ai.test.application.agent.run;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.run.BackgroundTaskAutoContinuationApplicationService;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.SessionEventClock;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundTaskFailWakeProjectionTest {

    @Test
    public void failWakeKeepsSnapshotProjectionsWhenAnotherRunTookOver() {
        String sessionId = "session-fail-wake-active";
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        TrackingProjection wakeProjection = projection(sessionId, "request-wake");
        TrackingProjection otherProjection = projection(sessionId, "request-other");
        wakeProjection.bindRegistry(projections);
        otherProjection.bindRegistry(projections);
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        activeRuns.begin("request-new", sessionId, "user-1");

        BackgroundTaskAutoContinuationApplicationService service =
                service(activeRuns, projections);
        failWake(service, sessionId, "request-wake", wakeProjection);

        Assert.assertEquals(1, wakeProjection.errorCount.get());
        Assert.assertEquals("新 run 已接管时不得关闭快照中的合法投影",
                0, otherProjection.completeCount.get());
        Assert.assertFalse(otherProjection.isClosed());
    }

    @Test
    public void failWakeWithoutActiveRunOnlyClosesSnapshotAndNeverReclosesKeep() {
        String sessionId = "session-fail-wake-idle";
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        TrackingProjection wakeProjection = projection(sessionId, "request-wake");
        TrackingProjection staleProjection = projection(sessionId, "request-stale");
        wakeProjection.bindRegistry(projections);
        staleProjection.bindRegistry(projections);

        BackgroundTaskAutoContinuationApplicationService service =
                service(new ActiveAgentRunRegistry(), projections);
        failWake(service, sessionId, "request-wake", wakeProjection);

        Assert.assertEquals(1, wakeProjection.errorCount.get());
        Assert.assertEquals("自己那条只通过 completeWithError 关闭一次",
                0, wakeProjection.completeCount.get());
        Assert.assertEquals(1, staleProjection.completeCount.get());
        Assert.assertTrue(staleProjection.isClosed());
    }

    @Test
    public void failWakeLeavesProjectionCreatedAfterSnapshotLive() {
        String sessionId = "session-fail-wake-late";
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        TrackingProjection wakeProjection = projection(sessionId, "request-wake");
        TrackingProjection staleProjection = projection(sessionId, "request-stale");
        TrackingProjection lateProjection = projection(sessionId, "request-late");
        wakeProjection.bindRegistry(projections);
        staleProjection.bindRegistry(projections);
        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry() {
            @Override
            public Optional<ActiveRun> findBySessionId(String ignoredSessionId) {
                lateProjection.bindRegistry(projections);
                return Optional.empty();
            }
        };

        BackgroundTaskAutoContinuationApplicationService service =
                service(activeRuns, projections);
        failWake(service, sessionId, "request-wake", wakeProjection);

        Assert.assertEquals(1, staleProjection.completeCount.get());
        Assert.assertEquals("快照之后新建的投影不能被旧快照误关",
                0, lateProjection.completeCount.get());
        Assert.assertFalse(lateProjection.isClosed());
    }

    private static BackgroundTaskAutoContinuationApplicationService service(
            ActiveAgentRunRegistry activeRuns,
            SessionProjectionRegistry projections) {
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        return new BackgroundTaskAutoContinuationApplicationService(
                readRepository,
                Mockito.mock(IAgentDispatchService.class),
                activeRuns,
                (ignoredSessionId, ignoredFrame) -> {
                },
                new AgentRunLaunchGate(0),
                new SessionEventClock(
                        readRepository,
                        Mockito.mock(IExecutionLedgerWriteRepository.class)),
                projections,
                new CuteConfig());
    }

    private static void failWake(BackgroundTaskAutoContinuationApplicationService service,
                                 String sessionId,
                                 String requestId,
                                 AgentStreamProjection projection) {
        ReflectionTestUtils.invokeMethod(
                service, "failWake", sessionId, requestId, projection,
                new IllegalStateException("dispatch failed"));
    }

    private static TrackingProjection projection(String sessionId, String requestId) {
        return new TrackingProjection(AgentExecutionCommand.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .userId("user-1")
                .build());
    }

    private static final class TrackingProjection extends AgentStreamProjection {

        private final AtomicInteger completeCount = new AtomicInteger();
        private final AtomicInteger errorCount = new AtomicInteger();

        private TrackingProjection(AgentExecutionCommand request) {
            super(null, request, Map.of(), null, null, null);
        }

        @Override
        public void complete() {
            completeCount.incrementAndGet();
            super.complete();
        }

        @Override
        public void completeWithError(Throwable throwable) {
            errorCount.incrementAndGet();
            super.completeWithError(throwable);
        }
    }
}
