package org.wwz.ai.test.stream;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;

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
}
