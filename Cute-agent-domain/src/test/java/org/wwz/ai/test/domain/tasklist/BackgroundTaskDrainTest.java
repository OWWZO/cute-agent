package org.wwz.ai.test.domain.tasklist;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.List;
import java.util.Set;

/**
 * BaseAgent 步进边界通知注入：队列优先、notified=0 补投、按 taskId 去重。
 */
public class BackgroundTaskDrainTest {

    @After
    public void tearDown() {
        SessionNotificationHub.clearAll();
    }

    @Test
    public void drainsQueuedNotificationAsModelOnlyMessage() {
        TestAgent agent = newAgent();
        SessionNotificationHub.offer(agent.getContext().getSessionId(), notification("task-1"));

        agent.drain();

        List<Message> messages = agent.getMemory().getMessages();
        Assert.assertEquals(1, messages.size());
        Assert.assertTrue(Boolean.TRUE.equals(messages.get(0).getModelOnly()));
        Assert.assertTrue(messages.get(0).getContent().contains("task-notification"));
        Assert.assertTrue(messages.get(0).getContent().contains("task-1"));
        Set<String> consumed = agent.getContext().control().drainConsumedBackgroundNotifications();
        Assert.assertTrue(consumed.contains("task-1"));
    }

    @Test
    public void backfillsTerminalTaskWithoutQueueEntry() {
        TestAgent agent = newAgent();
        RuntimeBackgroundTaskRegistry registry = agent.getContext().requireBackgroundTasks();
        RuntimeBackgroundTask task = registry.registerLocalAgent("调研", "general-purpose", "prompt");
        registry.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-1")
                .content("调研结果")
                .build());

        // 首次 drain：队列为空，从 registry 补投。
        agent.drain();
        Assert.assertEquals(1, agent.getMemory().getMessages().size());
        Assert.assertTrue(agent.getMemory().getMessages().get(0).getContent().contains("调研结果"));

        // 同一 run 内后续 step 不重复补投。
        agent.drain();
        Assert.assertEquals(1, agent.getMemory().getMessages().size());
    }

    @Test
    public void alreadyNotifiedTaskIsNotBackfilledAgain() {
        TestAgent agent = newAgent();
        RuntimeBackgroundTaskRegistry registry = agent.getContext().requireBackgroundTasks();
        RuntimeBackgroundTask task = registry.registerLocalAgent("调研", "general-purpose", "prompt");
        registry.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-1")
                .build());
        registry.markNotified(List.of(task.getId()));

        agent.drain();
        Assert.assertTrue(agent.getMemory().getMessages().isEmpty());
    }

    private static TestAgent newAgent() {
        String sessionId = "sess-drain-" + System.nanoTime();
        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .requestId("req-" + sessionId)
                .build();
        return new TestAgent(context);
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(taskId, "agent-1", null, "general-purpose", "调研",
                RuntimeBackgroundTask.STATUS_COMPLETED, "完成", null, 1, 10L, null, 0L);
    }

    private static final class TestAgent extends BaseAgent {

        private TestAgent(AgentContext context) {
            setContext(context);
        }

        @Override
        public String step() {
            return "noop";
        }

        private void drain() {
            drainBackgroundTaskNotificationsIntoMemory();
        }
    }
}
