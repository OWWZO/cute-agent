package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;

public class SessionInboxBaseAgentDrainTest {

    @Test
    public void drainsUserBeforeBackgroundAndTracksBothForAck() {
        String sessionId = "sess-inbox-drain";
        String requestId = "req-inbox-drain";
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.claimPending(sessionId, requestId, SessionInboxItem.KIND_USER_MESSAGE, 100))
                .thenReturn(List.of(userItem(1L)));
        when(inbox.claimPending(sessionId, requestId, SessionInboxItem.KIND_BACKGROUND_NOTIFICATION, 100))
                .thenReturn(List.of(backgroundItem(2L)));

        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        TestAgent agent = new TestAgent(context);

        agent.drainUser();
        agent.drainBackground();

        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(2, messages.size());
        Assert.assertTrue(messages.get(0).getContent().contains("用户指导"));
        Assert.assertFalse(Boolean.TRUE.equals(messages.get(0).getModelOnly()));
        Assert.assertTrue(messages.get(1).getContent().contains("task-1"));
        Assert.assertTrue(Boolean.TRUE.equals(messages.get(1).getModelOnly()));

        Set<Long> consumed = context.control().drainConsumedInboxItemIds();
        Assert.assertEquals(Set.of(1L, 2L), consumed);
    }

    @Test
    public void ignoresSameRunDuplicateClaimAndStillDrainsMemoryQueue() {
        String sessionId = "sess-inbox-duplicate";
        String requestId = "req-inbox-duplicate";
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.claimPending(sessionId, requestId, SessionInboxItem.KIND_USER_MESSAGE, 100))
                .thenReturn(List.of(userItem(1L)));

        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        context.control().offerInject(org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage.builder()
                .text("协调者指导")
                .source("coordinator")
                .build());
        TestAgent agent = new TestAgent(context);

        agent.drainUser();
        agent.drainUser();

        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(2, messages.size());
        Assert.assertTrue(messages.get(0).getContent().contains("用户指导"));
        Assert.assertTrue(messages.get(1).getContent().contains("协调者指导"));
        Assert.assertEquals(Set.of(1L), context.control().drainConsumedInboxItemIds());
    }

    @Test
    public void malformedInboxItemsAreQueuedForAckInsteadOfRepeatingForever() {
        String sessionId = "sess-inbox-malformed";
        String requestId = "req-inbox-malformed";
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.claimPending(sessionId, requestId, SessionInboxItem.KIND_USER_MESSAGE, 100))
                .thenReturn(List.of(userItemWithPayload(1L, "{broken")));
        when(inbox.claimPending(sessionId, requestId,
                SessionInboxItem.KIND_BACKGROUND_NOTIFICATION, 100))
                .thenReturn(List.of(backgroundItemWithPayload(2L, "{broken")));

        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        TestAgent agent = new TestAgent(context);

        agent.drainUser();
        agent.drainBackground();

        Assert.assertEquals(Set.of(1L, 2L), context.control().drainConsumedInboxItemIds());
        Assert.assertTrue(agent.getMemory().getMessages().isEmpty());
    }

    private static SessionInboxItem userItem(long id) {
        return SessionInboxItem.builder()
                .id(id)
                .sessionId("sess-inbox-drain")
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("client-1")
                .payloadJson(SessionInboxPayloads.userMessage("用户指导"))
                .build();
    }

    private static SessionInboxItem backgroundItem(long id) {
        TaskNotification notification = new TaskNotification(
                "task-1", "agent-1", null, "general-purpose", "调研",
                "completed", "完成", null, 1, 10L, null, 0L);
        return SessionInboxItem.builder()
                .id(id)
                .sessionId("sess-inbox-drain")
                .kind(SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                .visibility(SessionInboxItem.VISIBILITY_MODEL_ONLY)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("background:task-1")
                .payloadJson(SessionInboxPayloads.backgroundNotification(notification))
                .build();
    }

    private static SessionInboxItem userItemWithPayload(long id, String payload) {
        return SessionInboxItem.builder()
                .id(id)
                .sessionId("sess-inbox-malformed")
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("client-malformed")
                .payloadJson(payload)
                .build();
    }

    private static SessionInboxItem backgroundItemWithPayload(long id, String payload) {
        return SessionInboxItem.builder()
                .id(id)
                .sessionId("sess-inbox-malformed")
                .kind(SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                .visibility(SessionInboxItem.VISIBILITY_MODEL_ONLY)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("background-malformed")
                .payloadJson(payload)
                .build();
    }

    private static final class TestAgent extends BaseAgent {

        private TestAgent(AgentContext context) {
            setContext(context);
        }

        @Override
        public String step() {
            return "noop";
        }

        private void drainUser() {
            drainPendingInjectsIntoMemory();
        }

        private void drainBackground() {
            drainBackgroundTaskNotificationsIntoMemory();
        }
    }
}
