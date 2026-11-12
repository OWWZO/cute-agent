package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * 进程内按 session 排队的后台任务完成通知。
 * <p>跨 run 存活：主 Agent 的下一步边界 drain 注入，或由唤醒轮第一步取走；
 * 进程重启后丢失的待投递通知由 {@code ai_agent_background_task.notified=0} 补投。</p>
 */
public final class SessionNotificationHub {

    /** 与 SessionBackgroundTaskHub 对齐的进程级 session 上限。 */
    private static final int MAX_SESSIONS = 1024;
    /** 与 SessionAgentMailboxHub 对齐的空闲超时和惰性清理间隔。 */
    private static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private static final ConcurrentHashMap<String, NotificationQueue> BY_SESSION =
            new ConcurrentHashMap<>();
    private static final Object CLEANUP_LOCK = new Object();
    private static long lastCleanupAtMs;

    private SessionNotificationHub() {
    }

    public static void offer(String sessionId, TaskNotification notification) {
        if (notification == null || StringUtils.isBlank(notification.taskId())) {
            return;
        }
        String key = keyFor(sessionId);
        maybeCleanupExpired();
        BY_SESSION.compute(key, (ignored, queue) -> {
            NotificationQueue box = queue == null ? new NotificationQueue() : queue;
            box.offer(notification);
            return box;
        });
        evictEmptyOverflow(key);
    }

    /** 取出并清空当前 session 的待投递通知（FIFO）。 */
    public static List<TaskNotification> drain(String sessionId) {
        List<TaskNotification> drained = new ArrayList<>();
        BY_SESSION.computeIfPresent(keyFor(sessionId), (key, queue) -> {
            queue.drainInto(drained);
            return queue.isEmpty() ? null : queue;
        });
        return drained;
    }

    public static boolean hasPending(String sessionId) {
        NotificationQueue queue = BY_SESSION.get(keyFor(sessionId));
        return queue != null && !queue.isEmpty();
    }

    public static void evict(String sessionId) {
        BY_SESSION.remove(keyFor(sessionId));
    }

    /** 测试清理用。 */
    public static void clearAll() {
        BY_SESSION.clear();
    }

    /** 条目数供清理回归测试观察，不改变 offer/drain 语义。 */
    public static int trackedSessionCount() {
        return BY_SESSION.size();
    }

    /** 测试推进空闲时间；生产仍由真实时钟触发惰性清理。 */
    public static void expireForTest() {
        BY_SESSION.values().forEach(queue -> queue.expire());
    }

    /** 测试触发一次清理；并发安全性仍由 compute + 队列锁保证。 */
    public static void cleanupExpiredForTest() {
        removeExpiredEmpty(System.currentTimeMillis());
    }

    /** 测试推进空闲时间并清理，用于 offer 与清理并发压测。 */
    public static void expireForTestAndCleanup() {
        expireForTest();
        cleanupExpiredForTest();
    }

    private static void maybeCleanupExpired() {
        long now = System.currentTimeMillis();
        synchronized (CLEANUP_LOCK) {
            if (now - lastCleanupAtMs < CLEANUP_INTERVAL_MS) {
                return;
            }
            lastCleanupAtMs = now;
        }
        removeExpiredEmpty(now);
    }

    /** 只在 compute 锁内确认队列仍为空且仍过期，才移除条目。 */
    private static void removeExpiredEmpty(long now) {
        for (String key : BY_SESSION.keySet()) {
            BY_SESSION.computeIfPresent(key, (ignored, queue) ->
                    queue.isExpiredEmpty(now) ? null : queue);
        }
    }

    /** 超限只移除空队列；尚未 drain 的通知绝不淘汰。 */
    private static void evictEmptyOverflow(String currentKey) {
        if (BY_SESSION.size() <= MAX_SESSIONS) {
            return;
        }
        for (String key : BY_SESSION.keySet()) {
            if (BY_SESSION.size() <= MAX_SESSIONS || key.equals(currentKey)) {
                continue;
            }
            BY_SESSION.computeIfPresent(key, (ignored, queue) ->
                    queue.isEmpty() ? null : queue);
        }
    }

    /** 与 {@link SessionBackgroundTaskHub#keyFor(String)} 保持同一套会话归属规则。 */
    public static String keyFor(String sessionId) {
        return StringUtils.defaultIfBlank(sessionId, "default").trim();
    }

    private static final class NotificationQueue {
        private final ConcurrentLinkedQueue<TaskNotification> queue =
                new ConcurrentLinkedQueue<>();
        private volatile long lastAccessMs = System.currentTimeMillis();

        private synchronized void offer(TaskNotification notification) {
            queue.offer(notification);
            lastAccessMs = System.currentTimeMillis();
        }

        private synchronized void drainInto(List<TaskNotification> drained) {
            TaskNotification next;
            while ((next = queue.poll()) != null) {
                drained.add(next);
            }
            lastAccessMs = System.currentTimeMillis();
        }

        private synchronized boolean isEmpty() {
            return queue.isEmpty();
        }

        private synchronized boolean isExpiredEmpty(long now) {
            return queue.isEmpty() && now - lastAccessMs > IDLE_TIMEOUT_MS;
        }

        private synchronized void expire() {
            lastAccessMs = 0L;
        }
    }
}
