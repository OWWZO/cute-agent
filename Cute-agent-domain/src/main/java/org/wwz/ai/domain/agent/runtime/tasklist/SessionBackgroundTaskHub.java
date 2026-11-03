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

    /**
     * 单个进程最多保留的 session registry。超过后优先淘汰没有 running 任务的会话；
     * 运行中的任务不会被淘汰，因此真实并发数仍是最终上限。
     */
    private static final int MAX_SESSIONS = 1024;

    private static final ConcurrentHashMap<String, RuntimeBackgroundTaskRegistry> BY_SESSION =
            new ConcurrentHashMap<>();

    private SessionBackgroundTaskHub() {
    }

    public static RuntimeBackgroundTaskRegistry getOrCreate(String sessionId,
                                                             TasklistPersistencePort persistence) {
        String key = keyFor(sessionId);
        RuntimeBackgroundTaskRegistry existing = BY_SESSION.get(key);
        if (existing != null) {
            evictIdleOverflow(key);
            return existing;
        }
        // 构造会 hydrate（DB 读），必须在 bin lock 之外完成；并发创建时只保留先发布者。
        RuntimeBackgroundTaskRegistry created = new RuntimeBackgroundTaskRegistry(key, persistence);
        RuntimeBackgroundTaskRegistry raced = BY_SESSION.putIfAbsent(key, created);
        evictIdleOverflow(key);
        return raced != null ? raced : created;
    }

    /**
     * 静态 map 没有外部清理入口时，长跑服务的会话数会无界增长。这里按上限做惰性淘汰：
     * 只移除没有 running 任务的 registry；已结束任务仍可通过持久化端口跨轮找回。
     */
    private static void evictIdleOverflow(String currentKey) {
        if (BY_SESSION.size() <= MAX_SESSIONS) {
            return;
        }
        for (String key : BY_SESSION.keySet()) {
            if (BY_SESSION.size() <= MAX_SESSIONS) {
                return;
            }
            if (key.equals(currentKey)) {
                continue;
            }
            RuntimeBackgroundTaskRegistry registry = BY_SESSION.get(key);
            if (registry != null && registry.listRunning().isEmpty()) {
                BY_SESSION.remove(key, registry);
            }
        }
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

    /**
     * 与 {@link #getOrCreate(String, TasklistPersistencePort)} 使用同一 key。
     * <p>以前这里在 sessionId 为空时回退到 requestId，导致创建（"default"）与查询
     * （requestId）落在不同 registry，运行中的后台任务被误报为不存在。</p>
     */
    public static String keyFor(String sessionId, String requestId) {
        return keyFor(sessionId);
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
