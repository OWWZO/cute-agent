package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPendingHint;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;

/** 空闲 step 边界不查库；有提示时两种消息仍按用户优先、后台通知其次认领。 */
public class SessionInboxClaimBudgetTest {

    @Before
    public void setUp() {
        SessionInboxPendingHint.clearAll();
    }

    @Test
    public void cleanStepBoundarySkipsBothDbClaims() {
        String sessionId = "sess-clean-" + System.nanoTime();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        TestAgent agent = newAgent(sessionId, "req-clean", inbox);

        agent.drainUser();
        agent.drainBackground();
        Mockito.clearInvocations(inbox);
        agent.drainUser();
        agent.drainBackground();

        Mockito.verifyNoInteractions(inbox);
        Assert.assertTrue(agent.getContext().control().drainConsumedInboxItemIds().isEmpty());
    }

    @Test
    public void pendingUserAndBackgroundItemsAreClaimedAndInjected() {
        String sessionId = "sess-pending-" + System.nanoTime();
        String requestId = "req-pending";
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.claimPending(sessionId, requestId, SessionInboxItem.KIND_USER_MESSAGE, 100))
                .thenReturn(List.of(userItem(1L, sessionId)));
        when(inbox.claimPending(sessionId, requestId,
                SessionInboxItem.KIND_BACKGROUND_NOTIFICATION, 100))
                .thenReturn(List.of(backgroundItem(2L, sessionId)));
        TestAgent agent = newAgent(sessionId, requestId, inbox);

        agent.drainUser();
        agent.drainBackground();

        Mockito.verify(inbox).claimPending(
                sessionId, requestId, SessionInboxItem.KIND_USER_MESSAGE, 100);
        Mockito.verify(inbox).claimPending(
                sessionId, requestId, SessionInboxItem.KIND_BACKGROUND_NOTIFICATION, 100);
        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(2, messages.size());
        Assert.assertTrue(messages.get(0).getContent().contains("用户指导"));
        Assert.assertFalse(Boolean.TRUE.equals(messages.get(0).getModelOnly()));
        Assert.assertTrue(messages.get(1).getContent().contains("task-1"));
        Assert.assertTrue(Boolean.TRUE.equals(messages.get(1).getModelOnly()));
        Assert.assertEquals(Set.of(1L, 2L),
                agent.getContext().control().drainConsumedInboxItemIds());
    }

    @Test
    public void emptyClaimsClearHintForFollowingSteps() {
        String sessionId = "sess-empty-" + System.nanoTime();
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        TestAgent agent = newAgent(sessionId, "req-empty", inbox);

        agent.drainUser();
        agent.drainBackground();
        agent.drainUser();
        agent.drainBackground();

        Mockito.verify(inbox, Mockito.times(1)).claimPending(
                sessionId, "req-empty", SessionInboxItem.KIND_USER_MESSAGE, 100);
        Mockito.verify(inbox, Mockito.times(1)).claimPending(
                sessionId, "req-empty", SessionInboxItem.KIND_BACKGROUND_NOTIFICATION, 100);
        Assert.assertFalse(SessionInboxPendingHint.mayHavePending(sessionId));
    }

    @Test
    public void freshWriterInvalidatesCleanHint() {
        String sessionId = "sess-writer-" + System.nanoTime();
        SessionInboxPendingHint.markCleanIfUnchanged(
                sessionId, SessionInboxPendingHint.snapshot());
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        SessionInboxPendingHint.finishWrite(sessionId, write, true);

        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        newAgent(sessionId, "req-writer", inbox).drainUser();

        Mockito.verify(inbox).claimPending(
                sessionId, "req-writer", SessionInboxItem.KIND_USER_MESSAGE, 100);
    }

    private static TestAgent newAgent(String sessionId, String requestId, SessionInboxPort inbox) {
        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        return new TestAgent(context);
    }

    private static SessionInboxItem userItem(long id, String sessionId) {
        return SessionInboxItem.builder()
                .id(id)
                .sessionId(sessionId)
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("client-1")
                .payloadJson(SessionInboxPayloads.userMessage("用户指导"))
                .build();
    }

    private static SessionInboxItem backgroundItem(long id, String sessionId) {
        TaskNotification notification = new TaskNotification(
                "task-1", "agent-1", null, "general-purpose", "调研",
                "completed", "完成", null, 1, 10L, null, 0L);
        return SessionInboxItem.builder()
                .id(id)
                .sessionId(sessionId)
                .kind(SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                .visibility(SessionInboxItem.VISIBILITY_MODEL_ONLY)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("background:task-1")
                .payloadJson(SessionInboxPayloads.backgroundNotification(notification))
                .build();
    }

    private static final class TestAgent extends BaseAgent {

        private TestAgent(AgentContext context) {
            setContext(context);
            setMaxSteps(1);
        }

        @Override
        public String step() {
            setState(AgentState.FINISHED);
            return "done";
        }

        private void drainUser() {
            drainPendingInjectsIntoMemory();
        }

        private void drainBackground() {
            drainBackgroundTaskNotificationsIntoMemory();
        }
    }
}
