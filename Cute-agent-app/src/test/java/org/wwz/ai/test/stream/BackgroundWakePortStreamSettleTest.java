package org.wwz.ai.test.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BackgroundWakePortStreamSettleTest {

    @Test
    public void pendingNotificationSettlesWhenWakePortIsMissing() throws Exception {
        String sessionId = "sess-no-wake-" + System.nanoTime();
        RecordingPrinter printer = new RecordingPrinter();
        AgentContext parent = parentWithoutWakePort(sessionId, printer);
        ActiveAgentRunRegistry runRegistry = new ActiveAgentRunRegistry();
        runRegistry.begin(parent.getRequestId(), sessionId, null);
        SessionNotificationHub.offer(sessionId, notification("task-no-wake"));
        try {
            Assert.assertTrue("没有唤醒端口时必须允许释放父 run",
                    BackgroundSubAgentExecutor.shouldSettleParentStream(parent));
            emitBackgroundSettlement(parent, runRegistry);
            Assert.assertTrue(printer.messageTypes.contains("stream_settle"));
            Assert.assertFalse(runRegistry.find(parent.getRequestId()).isPresent());
        } finally {
            SessionNotificationHub.evict(sessionId);
            SessionBackgroundTaskHub.evict(sessionId);
        }
    }

    @Test
    public void pendingNotificationKeepsStreamOpenWhenWakePortExists() {
        String sessionId = "sess-with-wake-" + System.nanoTime();
        AgentContext parent = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-with-wake")
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .backgroundTaskWakePort(new NoopWakePort())
                        .build())
                .build();
        parent.markTurnClosed();
        SessionNotificationHub.offer(sessionId, notification("task-with-wake"));
        boolean previous = StreamSettlePolicy.deferBackgroundSettle();
        StreamSettlePolicy.setDeferBackgroundSettle(true);
        try {
            Assert.assertFalse("有唤醒端口且通知未消费时不得提前关流",
                    BackgroundSubAgentExecutor.shouldSettleParentStream(parent));
        } finally {
            StreamSettlePolicy.setDeferBackgroundSettle(previous);
            SessionNotificationHub.evict(sessionId);
            SessionBackgroundTaskHub.evict(sessionId);
        }
    }

    private static AgentContext parentWithoutWakePort(String sessionId, Printer printer) {
        AgentContext parent = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-no-wake")
                .printer(printer)
                .runtimeDependencies(CuteRuntimeDependencies.builder().build())
                .build();
        parent.markTurnClosed();
        return parent;
    }

    private static void emitBackgroundSettlement(AgentContext parent,
                                                 ActiveAgentRunRegistry runRegistry) throws Exception {
        Method emit = BackgroundSubAgentExecutor.class.getDeclaredMethod(
                "emitBackgroundSettlement",
                AgentContext.class, String.class, String.class, String.class, String.class,
                String.class, org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult.class,
                boolean.class, ActiveAgentRunRegistry.class, String.class, boolean.class);
        emit.setAccessible(true);
        emit.invoke(null, parent, null, null, null, null, null, null, true, runRegistry, null, true);
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(taskId, "agent-1", null, "general-purpose", "后台任务",
                "completed", "done", null, 1, 10L, null, 1L);
    }

    private static final class NoopWakePort implements BackgroundTaskWakePort {
        @Override
        public boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event) {
            return true;
        }

        @Override
        public void onRunSettled(String sessionId) {
        }
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
