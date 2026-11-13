package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

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
    /** 刚领取的 registry 可能还没登记 running 任务，5 秒内不参与空闲淘汰。 */
    public static final long CLAIM_GRACE_MS = 5_000L;

    private static final ConcurrentHashMap<String, RuntimeBackgroundTaskRegistry> BY_SESSION =
            new ConcurrentHashMap<>();
    /** 领取记录与 BY_SESSION 同步清理；每个线程只保留最新一条，避免连续创建阻塞淘汰。 */
    private static final ConcurrentHashMap<Long, Claim> CLAIMED_AT = new ConcurrentHashMap<>();

    private SessionBackgroundTaskHub() {
    }

    public static RuntimeBackgroundTaskRegistry getOrCreate(String sessionId,
                                                             TasklistPersistencePort persistence) {
        return getOrCreate(sessionId, null, persistence);
    }

    public static RuntimeBackgroundTaskRegistry getOrCreate(String sessionId,
                                                             String requestId,
                                                             TasklistPersistencePort persistence) {
        String key = keyFor(sessionId, requestId);
        long threadId = Thread.currentThread().threadId();
        claim(threadId, key);
        RuntimeBackgroundTaskRegistry existing = BY_SESSION.get(key);
        if (existing != null) {
            evictIdleOverflow(key);
            return existing;
        }
        // 构造会 hydrate（DB 读），必须在 bin lock 之外完成；并发创建时只保留先发布者。
        RuntimeBackgroundTaskRegistry created = new RuntimeBackgroundTaskRegistry(key, persistence);
        claim(threadId, key);
        RuntimeBackgroundTaskRegistry raced = BY_SESSION.putIfAbsent(key, created);
        evictIdleOverflow(key);
        return raced != null ? raced : created;
    }

    private static void claim(long threadId, String key) {
        CLAIMED_AT.put(threadId, new Claim(key, System.currentTimeMillis()));
    }

    /**
     * 静态 map 没有外部清理入口时，长跑服务的会话数会无界增长。这里按上限做惰性淘汰：
     * 只移除没有 running 任务的 registry；已结束任务仍可通过持久化端口跨轮找回。
     */
    private static void evictIdleOverflow(String currentKey) {
        if (BY_SESSION.size() <= MAX_SESSIONS) {
            return;
        }
        long now = System.currentTimeMillis();
        long evictorThreadId = Thread.currentThread().threadId();
        for (String key : BY_SESSION.keySet()) {
            if (BY_SESSION.size() <= MAX_SESSIONS) {
                return;
            }
            if (key.equals(currentKey)) {
                continue;
            }
            if (isRecentlyClaimedByOtherThread(key, now, evictorThreadId)) {
                continue;
            }
            RuntimeBackgroundTaskRegistry removed = BY_SESSION.computeIfPresent(key, (k, registry) ->
                    registry.listRunning().isEmpty() ? null : registry);
            if (removed == null) {
                CLAIMED_AT.entrySet().removeIf(entry -> key.equals(entry.getValue().key));
            }
        }
    }

    private static boolean isRecentlyClaimedByOtherThread(String key, long now, long evictorThreadId) {
        for (Map.Entry<Long, Claim> entry : CLAIMED_AT.entrySet()) {
            if (entry.getKey() == evictorThreadId) {
                continue;
            }
            Claim claim = entry.getValue();
            if (key.equals(claim.key) && now - claim.claimedAtMs < CLAIM_GRACE_MS) {
                return true;
            }
        }
        return false;
    }

    public static final class Claim {
        private final String key;
        long claimedAtMs;

        private Claim(String key, long claimedAtMs) {
            this.key = key;
            this.claimedAtMs = claimedAtMs;
        }

        public void expire(long now) {
            claimedAtMs = now - CLAIM_GRACE_MS - 1;
        }

        public String key() {
            return key;
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
     * 空白 sessionId 一律回退到 requestId，与 {@link #getOrCreate(String, String, TasklistPersistencePort)}
     * 及 {@code AgentContext.requireBackgroundTasks()} 保持一致。
     */
    public static String keyFor(String sessionId, String requestId) {
        return StringUtils.defaultIfBlank(sessionId, StringUtils.defaultIfBlank(requestId, "default")).trim();
    }

    /** 测试或会话清理用 */
    public static void evict(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        String key = sessionId.trim();
        BY_SESSION.remove(key);
        CLAIMED_AT.entrySet().removeIf(entry -> key.equals(entry.getValue().key));
    }

    public static void clearAll() {
        BY_SESSION.clear();
        CLAIMED_AT.clear();
    }
}
