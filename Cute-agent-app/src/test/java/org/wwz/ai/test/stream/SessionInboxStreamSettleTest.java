package org.wwz.ai.test.stream;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SessionInboxStreamSettleTest {

    @Test
    public void outstandingInboxKeepsParentStreamOpenForWakeFrame() {
        String sessionId = "sess-inbox-settle-" + System.nanoTime();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        Mockito.when(inbox.hasOutstanding(sessionId)).thenReturn(true);
        AgentContext parent = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-inbox-settle")
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        parent.markTurnClosed();

        SessionBackgroundTaskHub.evict(sessionId);
        SessionNotificationHub.evict(sessionId);
        try {
            Assert.assertFalse(BackgroundSubAgentExecutor.shouldSettleParentStream(parent));
        } finally {
            SessionBackgroundTaskHub.evict(sessionId);
            SessionNotificationHub.evict(sessionId);
        }
    }

    @Test
    public void inboxProbeFailureFallsBackToMemoryOnlySettlement() {
        String sessionId = "sess-inbox-probe-failure-" + System.nanoTime();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        Mockito.when(inbox.hasOutstanding(sessionId)).thenThrow(new IllegalStateException("db down"));
        AgentContext parent = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-inbox-probe-failure")
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        parent.markTurnClosed();

        Assert.assertTrue(BackgroundSubAgentExecutor.shouldSettleParentStream(parent));
    }

    @Test
    public void settlementClosesStreamWhenNoWakePathCanConsumeOutstandingInbox() throws Exception {
        String sessionId = "sess-inbox-no-wake-" + System.nanoTime();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        Mockito.when(inbox.hasOutstanding(sessionId)).thenReturn(true);
        RecordingPrinter printer = new RecordingPrinter();
        AgentContext parent = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-inbox-no-wake")
                .printer(printer)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        parent.markTurnClosed();
        ActiveAgentRunRegistry runRegistry = new ActiveAgentRunRegistry();
        runRegistry.begin(parent.getRequestId(), sessionId, null);

        Method emit = BackgroundSubAgentExecutor.class.getDeclaredMethod(
                "emitBackgroundSettlement",
                AgentContext.class, String.class, String.class, String.class, String.class,
                String.class, org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult.class,
                boolean.class, ActiveAgentRunRegistry.class, String.class, boolean.class);
        emit.setAccessible(true);
        emit.invoke(null, parent, null, null, null, null, null, null, true, runRegistry, null, true);

        Assert.assertTrue(printer.messageTypes.contains("stream_settle"));
        Assert.assertFalse(runRegistry.find(parent.getRequestId()).isPresent());
    }

    private static final class RecordingPrinter implements Printer {
        private final List<String> messageTypes = new ArrayList<>();

        @Override
        public void send(String messageId, String messageType, Object message,
                         String digitalEmployee, Boolean isFinal) {
            messageTypes.add(messageType);
        }

        @Override
        public void send(String messageId, String messageType, Object message,
                         Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            messageTypes.add(messageType);
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(org.wwz.ai.domain.agent.runtime.enums.AgentType agentType) {
        }
    }
}
