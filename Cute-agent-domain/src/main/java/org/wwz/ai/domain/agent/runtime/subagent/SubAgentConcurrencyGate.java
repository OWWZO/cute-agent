package org.wwz.ai.domain.agent.runtime.subagent;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 子 Agent 全局并发闸门。
 * 限制同时阻塞在 SubAgentRunner 中的嵌套 Agent 数量，避免打满 task/tool 池。
 */
public final class SubAgentConcurrencyGate {

    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final long DEFAULT_ACQUIRE_TIMEOUT_SECONDS = 30L;

    private final Semaphore semaphore;
    private final int maxConcurrent;
    private final long acquireTimeoutSeconds;
    /**
     * 同一线程内的父/子/孙调用复用最外层许可。
     * 否则父节点持有许可后再等子节点抢许可，会在 maxConcurrent 用尽时形成等待环。
     */
    private final ThreadLocal<Integer> heldDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * @param acquireTimeoutSeconds 获取许可的等待秒数；0 表示不等待（tryAcquire 立即返回），
     *                              负值会被归零，因此配置为负时等同「不等待」
     */
    public SubAgentConcurrencyGate(int maxConcurrent, long acquireTimeoutSeconds) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.acquireTimeoutSeconds = Math.max(0L, acquireTimeoutSeconds);
        this.semaphore = new Semaphore(this.maxConcurrent, true);
    }

    /**
     * 默认全局闸门。返回共享实例，确保未显式注入 gate 的调用方仍受同一个全局并发上限约束。
     */
    public static SubAgentConcurrencyGate defaults() {
        return DEFAULT_GATE;
    }

    private static final SubAgentConcurrencyGate DEFAULT_GATE =
            new SubAgentConcurrencyGate(DEFAULT_MAX_CONCURRENT, DEFAULT_ACQUIRE_TIMEOUT_SECONDS);

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public long getAcquireTimeoutSeconds() {
        return acquireTimeoutSeconds;
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * 在许可内执行；拿不到许可时返回 null（由调用方转失败结果）。
     */
    public <T> T runWithPermit(Supplier<T> action) throws InterruptedException {
        int depth = heldDepth.get();
        if (depth > 0) {
            heldDepth.set(depth + 1);
            try {
                return action.get();
            } finally {
                int remaining = heldDepth.get() - 1;
                if (remaining == 0) {
                    heldDepth.remove();
                } else {
                    heldDepth.set(remaining);
                }
            }
        }
        boolean acquired = acquireTimeoutSeconds == 0L
                ? semaphore.tryAcquire()
                : semaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
        if (!acquired) {
            return null;
        }
        heldDepth.set(1);
        try {
            return action.get();
        } finally {
            heldDepth.remove();
            semaphore.release();
        }
    }
}
