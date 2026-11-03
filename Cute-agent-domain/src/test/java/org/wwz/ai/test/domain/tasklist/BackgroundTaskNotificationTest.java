package org.wwz.ai.test.domain.tasklist;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemoryProjector;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.BackgroundTaskNotificationFormatter;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.List;

/**
 * 后台任务完成通知：claim 语义、队列 FIFO、XML 转义与截断。
 */
public class BackgroundTaskNotificationTest {

    @After
    public void tearDown() {
        SessionNotificationHub.clearAll();
    }

    @Test
    public void onlyTerminalTaskCanBeClaimedOnce() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = registry.registerLocalAgent("调研", "general-purpose", "prompt");

        Assert.assertFalse("running 任务不能 claim", registry.claimNotification(task.getId()));
        registry.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-1")
                .content("done")
                .build());

        Assert.assertTrue(registry.claimNotification(task.getId()));
        Assert.assertFalse("同一任务只入队一次", registry.claimNotification(task.getId()));
        RuntimeBackgroundTask stopped = registry.registerLocalAgent("停止", "general-purpose", "prompt");
        registry.stop(stopped.getId());
        Assert.assertFalse("stopped 不产生通知", registry.claimNotification(stopped.getId()));
    }

    @Test
    public void markNotifiedIsIdempotent() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = registry.registerLocalAgent("调研", "general-purpose", "prompt");
        registry.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-1")
                .build());

        Assert.assertFalse(Boolean.TRUE.equals(task.getNotified()));
        registry.markNotified(List.of(task.getId()));
        Assert.assertTrue(Boolean.TRUE.equals(task.getNotified()));
        registry.markNotified(List.of(task.getId()));
    }

    @Test
    public void hubDrainsInFifoOrderAndCanBeEvicted() {
        SessionNotificationHub.offer("s1", notification("t1"));
        SessionNotificationHub.offer("s1", notification("t2"));

        Assert.assertTrue(SessionNotificationHub.hasPending("s1"));
        List<TaskNotification> drained = SessionNotificationHub.drain("s1");
        Assert.assertEquals(2, drained.size());
        Assert.assertEquals("t1", drained.get(0).taskId());
        Assert.assertEquals("t2", drained.get(1).taskId());
        Assert.assertFalse(SessionNotificationHub.hasPending("s1"));

        SessionNotificationHub.offer("s2", notification("t3"));
        SessionNotificationHub.evict("s2");
        Assert.assertFalse(SessionNotificationHub.hasPending("s2"));
    }

    @Test
    public void formatterEscapesXmlAndTruncatesLongResult() {
        TaskNotification notification = new TaskNotification(
                "t<1>", "agent&1", null, "general-purpose", "des\"c",
                RuntimeBackgroundTask.STATUS_COMPLETED,
                "</task-notification>" + "x".repeat(130_000),
                null, 3, 1200L, null, 0L);

        String xml = BackgroundTaskNotificationFormatter.format(notification);
        Assert.assertTrue(xml.startsWith("<task-notification>"));
        Assert.assertTrue(xml.contains("<task-id>t&lt;1&gt;</task-id>"));
        Assert.assertTrue(xml.contains("<agent-id>agent&amp;1</agent-id>"));
        Assert.assertTrue(xml.contains("[truncated]"));
        Assert.assertTrue(xml.contains("tools=3"));
        Assert.assertTrue(xml.contains("duration=1200ms"));
        Assert.assertEquals(1, countOccurrences(xml, "</task-notification>"));
    }

    @Test
    public void failedNotificationIncludesError() {
        TaskNotification notification = new TaskNotification(
                "t1", "agent-1", null, null, "调研",
                RuntimeBackgroundTask.STATUS_FAILED,
                null, "boom", null, null, null, 0L);

        String xml = BackgroundTaskNotificationFormatter.format(notification);
        Assert.assertTrue(xml.contains("失败：boom"));
        Assert.assertTrue(xml.contains("<error>boom</error>"));
    }

    @Test
    public void workingMemoryProjectionKeepsModelOnlyVisibility() {
        WorkingMemoryProjector projector = new WorkingMemoryProjector();
        Message notification = Message.modelOnlyUserMessage("<task-notification>done</task-notification>");

        List<WorkingMemoryMessage> rows = projector.project(List.of(notification), "s1", "r1", 1L);
        Assert.assertEquals(1, rows.size());
        Assert.assertEquals("MODEL_ONLY", rows.get(0).getVisibility());
        Assert.assertEquals("background_notification", rows.get(0).getMessageKind());

        List<Message> hydrated = projector.hydrate(rows);
        Assert.assertEquals(1, hydrated.size());
        Assert.assertTrue(Boolean.TRUE.equals(hydrated.get(0).getModelOnly()));
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(taskId, "agent-" + taskId, null, null, "desc",
                RuntimeBackgroundTask.STATUS_COMPLETED, "ok", null, 1, 10L, null, 0L);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
