package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内按 session 共享后台任务 registry。
 * <p>
 * 同一 JVM 内跨 request 轮次可复用 Future / 内存态，避免「上一轮 Agent 后台跑着、
 * 下一轮 TaskOutput 新建空 registry → not_found」。
 * 跨进程/重启仍依赖 {@link TasklistPersistencePort}。
 */
public final class SessionBackgroundTaskHub {

    private static final ConcurrentHashMap<String, RuntimeBackgroundTaskRegistry> BY_SESSION =
            new ConcurrentHashMap<>();

    private SessionBackgroundTaskHub() {
    }

    public static RuntimeBackgroundTaskRegistry getOrCreate(String sessionId,
                                                             TasklistPersistencePort persistence) {
        String key = keyFor(sessionId);
        RuntimeBackgroundTaskRegistry existing = BY_SESSION.get(key);
        if (existing != null) {
            return existing;
        }
        // 构造会 hydrate（DB 读），必须在 bin lock 之外完成；并发创建时只保留先发布者。
        RuntimeBackgroundTaskRegistry created = new RuntimeBackgroundTaskRegistry(key, persistence);
        RuntimeBackgroundTaskRegistry raced = BY_SESSION.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    /** 当前 session 是否仍有 running 后台任务（用于延迟关闭主 SSE） */
    public static boolean hasRunning(String sessionId) {
        String key = keyFor(sessionId);
        RuntimeBackgroundTaskRegistry registry = BY_SESSION.get(key);
        return registry != null && !registry.listRunning().isEmpty();
    }

    public static boolean hasRunning(String sessionId, String requestId) {
        RuntimeBackgroundTaskRegistry registry = BY_SESSION.get(keyFor(sessionId, requestId));
        return registry != null && !registry.listRunning().isEmpty();
    }

    /** 与 AgentContext.requireBackgroundTasks 使用同一套会话回退规则。 */
    public static String keyFor(String sessionId) {
        return StringUtils.defaultIfBlank(sessionId, "default").trim();
    }

    public static String keyFor(String sessionId, String requestId) {
        return StringUtils.defaultIfBlank(sessionId,
                StringUtils.defaultIfBlank(requestId, "default")).trim();
    }

    /** 测试或会话清理用 */
    public static void evict(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        BY_SESSION.remove(sessionId.trim());
    }

    public static void clearAll() {
        BY_SESSION.clear();
    }
}
