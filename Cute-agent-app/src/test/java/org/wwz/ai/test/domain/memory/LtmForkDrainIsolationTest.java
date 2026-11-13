package org.wwz.ai.test.domain.memory;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.List;

import static org.mockito.Mockito.when;

public class LtmForkDrainIsolationTest {

    @After
    public void tearDown() {
        SessionNotificationHub.clearAll();
        SessionBackgroundTaskHub.clearAll();
    }

    @Test
    public void ltmForkMustNotDrainParentSessionControlMessages() {
        String parentSessionId = "sess-ltm-fork-parent";
        String forkRequestId = "req-ltm-fork";
        SessionInboxPort inbox = Mockito.mock(SessionInboxPort.class);
        when(inbox.claimPending(
                parentSessionId, forkRequestId, SessionInboxItem.KIND_USER_MESSAGE, 100))
                .thenReturn(List.of(userItem(parentSessionId)));

        AgentContext context = AgentContext.builder()
                .sessionId(parentSessionId)
                .requestId(forkRequestId)
                .ltmSideEffectsDisabled(Boolean.TRUE)
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .sessionInboxPort(inbox)
                        .build())
                .build();
        String parentSessionKey = SessionBackgroundTaskHub.keyFor(parentSessionId, "req-parent");
        SessionNotificationHub.offer(parentSessionKey, notification("task-parent"));
        TestAgent agent = new TestAgent(context);

        agent.drainUser();
        agent.drainBackground();

        Assert.assertTrue("LTM fork 抢走了父会话的后台通知",
                SessionNotificationHub.hasPending(parentSessionKey));
        Mockito.verifyNoInteractions(inbox);
        Assert.assertTrue("LTM fork 向自己的 memory 注入了父会话的中途指导",
                agent.getMemory().getMessages().isEmpty());
        Assert.assertEquals("task-parent",
                SessionNotificationHub.drain(parentSessionKey).get(0).taskId());
    }

    @Test
    public void childAgentMustStillDrainOwnInjectQueue() {
        String childSessionId = "sub-agent-own-inbox";
        AgentContext context = AgentContext.builder()
                .sessionId(childSessionId)
                .subAgentChildSessionId(childSessionId)
                .subAgentId("agent-own-inbox")
                .requestId("req-agent-own-inbox")
                .ltmSideEffectsDisabled(Boolean.FALSE)
                .build();
        context.offerInject(PendingInjectMessage.builder()
                .text("子 Agent 自己的中途指导")
                .source(PendingInjectMessage.SOURCE_COORDINATOR)
                .build());
        TestAgent agent = new TestAgent(context);

        agent.drainUser();

        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(1, messages.size());
        Assert.assertTrue(messages.get(0).getContent().contains("子 Agent 自己的中途指导"));
    }

    @Test
    public void mainAgentMustStillDrainParentBackgroundNotifications() {
        String parentSessionId = "sess-main-drain";
        AgentContext context = AgentContext.builder()
                .sessionId(parentSessionId)
                .requestId("req-main-drain")
                .ltmSideEffectsDisabled(Boolean.FALSE)
                .build();
        String parentSessionKey = SessionBackgroundTaskHub.keyFor(parentSessionId, "req-main-drain");
        SessionNotificationHub.offer(parentSessionKey, notification("task-main"));
        TestAgent agent = new TestAgent(context);

        agent.drainBackground();

        Assert.assertFalse("主 Agent 必须正常取走自己的后台通知",
                SessionNotificationHub.hasPending(parentSessionKey));
        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(1, messages.size());
        Assert.assertTrue(Boolean.TRUE.equals(messages.get(0).getModelOnly()));
        Assert.assertTrue(messages.get(0).getContent().contains("task-main"));
    }

    private static SessionInboxItem userItem(String sessionId) {
        return SessionInboxItem.builder()
                .id(1L)
                .sessionId(sessionId)
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_CLAIMED)
                .idempotencyKey("ltm-fork-parent-guidance")
                .payloadJson(SessionInboxPayloads.userMessage("父会话中途指导"))
                .build();
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(taskId, "agent-1", null, "general-purpose", "调研",
                "completed", "完成", null, 1, 10L, null, 0L);
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
