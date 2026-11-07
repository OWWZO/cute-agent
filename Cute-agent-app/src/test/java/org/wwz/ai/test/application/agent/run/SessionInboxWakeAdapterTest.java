package org.wwz.ai.test.application.agent.run;

import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.run.BackgroundTaskAutoContinuationApplicationService;
import org.wwz.ai.application.agent.run.BackgroundTaskWakeAdapter;
import org.wwz.ai.application.agent.run.SessionYieldGate;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SessionInboxWakeAdapterTest {

    @Test
    public void userInboxPendingSchedulesWakeWithDisplayQuery() {
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.hasHeldUserMessage("sess-user-wake")).thenReturn(false);
        when(inbox.hasDeliverableKind("sess-user-wake", "USER_MESSAGE")).thenReturn(true);
        when(inbox.peekFirstDeliverableUserText("sess-user-wake")).thenReturn("继续处理");

        BackgroundTaskAutoContinuationApplicationService continuation =
                Mockito.mock(BackgroundTaskAutoContinuationApplicationService.class);
        when(continuation.dispatchWake(
                eq("sess-user-wake"), any(), eq("user_inbox"), eq("继续处理"), any(Consumer.class)))
                .thenReturn(true);

        CuteConfig config = new CuteConfig();
        config.setBackgroundAutoContinuation(true);
        config.setBackgroundAutoContinuationDelayMs(0L);
        BackgroundTaskWakeAdapter adapter = new BackgroundTaskWakeAdapter(
                new ActiveAgentRunRegistry(),
                new SessionYieldGate() {
                    @Override
                    public boolean hasOpenYield(String ignoredSessionId) {
                        return false;
                    }
                },
                continuation,
                new SessionProjectionRegistry(),
                config);
        ReflectionTestUtils.setField(adapter, "sessionInboxPort", inbox);

        try {
            adapter.onInboxChanged("sess-user-wake");
            verify(continuation, timeout(1000)).dispatchWake(
                    eq("sess-user-wake"), any(), eq("user_inbox"), eq("继续处理"), any(Consumer.class));
        } finally {
            adapter.shutdown();
        }
    }
}
