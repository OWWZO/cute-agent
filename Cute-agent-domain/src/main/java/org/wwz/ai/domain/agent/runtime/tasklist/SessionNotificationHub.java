package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 进程内按 session 排队的后台任务完成通知。
 * <p>跨 run 存活：主 Agent 的下一步边界 drain 注入，或由唤醒轮第一步取走；
 * 进程重启后丢失的待投递通知由 {@code ai_agent_background_task.notified=0} 补投。</p>
 */
public final class SessionNotificationHub {

    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<TaskNotification>> BY_SESSION =
            new ConcurrentHashMap<>();

    private SessionNotificationHub() {
    }

    public static void offer(String sessionId, TaskNotification notification) {
        if (notification == null || StringUtils.isBlank(notification.taskId())) {
            return;
        }
        BY_SESSION.computeIfAbsent(keyFor(sessionId), key -> new ConcurrentLinkedQueue<>())
                .offer(notification);
    }

    /** 取出并清空当前 session 的待投递通知（FIFO）。 */
    public static List<TaskNotification> drain(String sessionId) {
        ConcurrentLinkedQueue<TaskNotification> queue = BY_SESSION.get(keyFor(sessionId));
        if (queue == null) {
            return List.of();
        }
        List<TaskNotification> drained = new ArrayList<>();
        TaskNotification next;
        while ((next = queue.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    public static boolean hasPending(String sessionId) {
        ConcurrentLinkedQueue<TaskNotification> queue = BY_SESSION.get(keyFor(sessionId));
        return queue != null && !queue.isEmpty();
    }

    public static void evict(String sessionId) {
        BY_SESSION.remove(keyFor(sessionId));
    }

    /** 测试清理用。 */
    public static void clearAll() {
        BY_SESSION.clear();
    }

    /** 与 {@link SessionBackgroundTaskHub#keyFor(String)} 保持同一套会话归属规则。 */
    public static String keyFor(String sessionId) {
        return StringUtils.defaultIfBlank(sessionId, "default").trim();
    }
}
