package org.wwz.ai.test.domain.tasklist;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 通知队列清理不得改变 FIFO、破坏跨 run 存活或丢失并发写入。 */
public class SessionNotificationHubCleanupTest {

    @After
    public void tearDown() {
        SessionNotificationHub.clearAll();
    }

    @Test
    public void drainingAllNotificationsRemovesSessionEntry() {
        SessionNotificationHub.offer("s1", notification("t1"));
        SessionNotificationHub.offer("s1", notification("t2"));

        Assert.assertEquals(List.of("t1", "t2"), taskIds(SessionNotificationHub.drain("s1")));
        Assert.assertEquals(0, SessionNotificationHub.trackedSessionCount());
        Assert.assertFalse(SessionNotificationHub.hasPending("s1"));

        SessionNotificationHub.offer("s1", notification("t3"));
        Assert.assertEquals("t3", SessionNotificationHub.drain("s1").get(0).taskId());
        Assert.assertEquals(0, SessionNotificationHub.trackedSessionCount());
    }

    @Test
    public void expiredIdleEntryIsCleanedUp() {
        SessionNotificationHub.offer("s-idle", notification("t1"));
        Assert.assertEquals(1, SessionNotificationHub.trackedSessionCount());
        Assert.assertEquals("t1", SessionNotificationHub.drain("s-idle").get(0).taskId());
        SessionNotificationHub.offer("s-idle", notification("t2"));
        SessionNotificationHub.drain("s-idle");
        SessionNotificationHub.offer("s-idle", notification("t3"));
        SessionNotificationHub.expireForTest();
        SessionNotificationHub.cleanupExpiredForTest();

        Assert.assertTrue("有通知的空闲条目不得被清理", SessionNotificationHub.hasPending("s-idle"));
        Assert.assertEquals(1, SessionNotificationHub.trackedSessionCount());
        Assert.assertEquals("t3", SessionNotificationHub.drain("s-idle").get(0).taskId());
        Assert.assertEquals(0, SessionNotificationHub.trackedSessionCount());
    }

    @Test
    public void expiredEmptyEntryIsCleanedUp() {
        SessionNotificationHub.offer("s-empty", notification("t1"));
        Assert.assertEquals("t1", SessionNotificationHub.drain("s-empty").get(0).taskId());
        SessionNotificationHub.expireForTest();
        SessionNotificationHub.cleanupExpiredForTest();

        Assert.assertFalse(SessionNotificationHub.hasPending("s-empty"));
        Assert.assertEquals(0, SessionNotificationHub.trackedSessionCount());
        SessionNotificationHub.offer("s-empty", notification("t2"));
        Assert.assertEquals("t2", SessionNotificationHub.drain("s-empty").get(0).taskId());
    }

    @Test
    public void concurrentOfferAndCleanupDoNotLoseMessages() throws Exception {
        int rounds = 50;
        int offersPerRound = 20;
        List<String> collected = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            String sessionId = "s-race-" + round;
            String taskPrefix = "w-" + round + "-";
            CountDownLatch start = new CountDownLatch(1);
            List<String> drainBatch = new ArrayList<>();
            Thread writer = new Thread(() -> {
                await(start);
                for (int i = 0; i < offersPerRound; i++) {
                    SessionNotificationHub.offer(sessionId, notification(taskPrefix + i));
                }
            });
            Thread cleaner = new Thread(() -> {
                await(start);
                for (int i = 0; i < offersPerRound; i++) {
                    synchronized (drainBatch) {
                        drainBatch.addAll(taskIds(SessionNotificationHub.drain(sessionId)));
                    }
                    SessionNotificationHub.cleanupExpiredForTest();
                    SessionNotificationHub.expireForTestAndCleanup();
                }
            });
            writer.start();
            cleaner.start();
            start.countDown();
            writer.join(10_000);
            cleaner.join(10_000);
            Assert.assertFalse(writer.isAlive());
            Assert.assertFalse(cleaner.isAlive());

            collected.addAll(taskIds(SessionNotificationHub.drain(sessionId)));
            synchronized (drainBatch) {
                collected.addAll(drainBatch);
            }
        }
        Assert.assertEquals(rounds * offersPerRound, collected.size());
        Assert.assertEquals(rounds * offersPerRound, collected.stream().distinct().count());
        Assert.assertTrue(collected.stream().allMatch(id -> id.startsWith("w-")));
        Assert.assertEquals(0, SessionNotificationHub.trackedSessionCount());
    }

    @Test
    public void overflowEvictionNeverDiscardsUndrainedNotifications() {
        for (int i = 0; i < 1024; i++) {
            SessionNotificationHub.offer("s-idle-" + i, notification("idle-" + i));
            Assert.assertTrue(SessionNotificationHub.drain("s-idle-" + i).isEmpty()
                    || !SessionNotificationHub.hasPending("s-idle-" + i));
        }
        for (int i = 0; i < 8; i++) {
            SessionNotificationHub.offer("s-pending-" + i, notification("pending-" + i));
        }

        Assert.assertTrue(SessionNotificationHub.trackedSessionCount() <= 1024);
        for (int i = 0; i < 8; i++) {
            Assert.assertTrue("尚未 drain 的通知必须保留",
                    SessionNotificationHub.hasPending("s-pending-" + i));
            Assert.assertEquals("pending-" + i,
                    SessionNotificationHub.drain("s-pending-" + i).get(0).taskId());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static List<String> taskIds(List<TaskNotification> notifications) {
        return notifications.stream().map(TaskNotification::taskId).toList();
    }

    private static TaskNotification notification(String taskId) {
        return new TaskNotification(taskId, "agent-1", null, "general-purpose", "调研",
                "completed", "完成", null, 1, 10L, null, 0L);
    }
}
