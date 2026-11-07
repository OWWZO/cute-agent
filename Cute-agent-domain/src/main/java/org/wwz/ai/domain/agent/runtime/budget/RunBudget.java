package org.wwz.ai.domain.agent.runtime.budget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run 级共享预算：工具调用前置门禁、80% 收尾和最终答案预留。
 *
 * <p>主 Agent、子 Agent、并发 fork 共享同一实例。没有挂载本对象的普通运行
 * 不改变既有行为。</p>
 */
public final class RunBudget {

    public record ToolDecision(boolean allowed,
                               int attempt,
                               boolean hardLimit,
                               String reason,
                               String message) {
    }

    private final Integer maxTotalTokens;
    private final Integer maxWallMs;
    private final Integer maxToolCalls;
    private final Integer maxSteps;
    private final double softRatio;
    private final long startedNanos;
    private final AtomicInteger totalTokens = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicReference<String> hardExceededReason = new AtomicReference<>();
    private final AtomicReference<String> softStopReason = new AtomicReference<>();
    private final AtomicBoolean finalizationStarted = new AtomicBoolean();
    private final AtomicReference<String> finalizationReason = new AtomicReference<>();
    private final AtomicInteger tokensAtFinalization = new AtomicInteger(-1);
    private final Object finalizationLock = new Object();
    private final Set<Object> finalizationOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Boolean> finalizationResults = new IdentityHashMap<>();

    private RunBudget(Integer maxTotalTokens,
                      Integer maxWallMs,
                      Integer maxToolCalls,
                      Integer maxSteps,
                      double softRatio) {
        this.maxTotalTokens = positiveOrNull(maxTotalTokens);
        this.maxWallMs = positiveOrNull(maxWallMs);
        this.maxToolCalls = positiveOrNull(maxToolCalls);
        this.maxSteps = positiveOrNull(maxSteps);
        this.softRatio = softRatio <= 0.0 || softRatio >= 1.0 ? 0.8 : softRatio;
        this.startedNanos = System.nanoTime();
    }

    public static RunBudget create(Integer maxTotalTokens,
                                   Integer maxWallMs,
                                   Integer maxToolCalls,
                                   Integer maxSteps) {
        return new RunBudget(maxTotalTokens, maxWallMs, maxToolCalls, maxSteps, 0.8);
    }

    /**
     * 记录一次 LLM 调用的真实用量。Token 可能已经超过硬上限，但调用本身无法回滚。
     */
    public void recordTokens(int tokens) {
        if (tokens <= 0) {
            return;
        }
        int used = totalTokens.addAndGet(tokens);
        if (maxTotalTokens != null && used > maxTotalTokens) {
            hardExceededReason.compareAndSet(null, "total_tokens");
        } else if (maxTotalTokens != null && used >= softTokenLimit()) {
            softStopReason.compareAndSet(null, "token_reserve");
        }
    }

    /**
     * 工具执行前原子占用一次调用额度。被拒绝时工具不会产生外部副作用。
     */
    public synchronized ToolDecision tryAcquireTool() {
        if (hardExceededReason.get() != null) {
            return denied(true, hardExceededReason.get(), hardStopMessage());
        }
        if (maxToolCalls != null && toolCalls.get() >= maxToolCalls) {
            hardExceededReason.compareAndSet(null, "tool_calls");
            return denied(true, "tool_calls", hardStopMessage());
        }
        if (maxWallMs != null && elapsedMs() >= softWallLimit()) {
            softStopReason.compareAndSet(null, "wall_time_reserve");
        }
        if (softStopReason.get() != null
                || (maxToolCalls != null && toolCalls.get() >= softToolLimit())) {
            String reason = softStopReason.get() == null ? "tool_reserve" : softStopReason.get();
            softStopReason.compareAndSet(null, reason);
            return denied(false, reason, softStopMessage());
        }
        int next = toolCalls.incrementAndGet();
        if (maxToolCalls != null && next > maxToolCalls) {
            toolCalls.decrementAndGet();
            hardExceededReason.compareAndSet(null, "tool_calls");
            return denied(true, "tool_calls", hardStopMessage());
        }
        return new ToolDecision(true, next, false, null, null);
    }

    /**
     * 每个 Agent 各允许领取一次收尾权；返回 null 表示当前不需要或该 Agent 已领取过。
     */
    public String claimFinalization(Object owner, int currentStep) {
        String reason = finalizationReason(currentStep);
        if (reason == null) {
            return null;
        }
        Object key = owner == null ? this : owner;
        synchronized (finalizationLock) {
            if (!finalizationOwners.add(key)) {
                return null;
            }
            finalizationStarted.set(true);
            finalizationReason.compareAndSet(null, reason);
            tokensAtFinalization.compareAndSet(-1, totalTokens.get());
        }
        return reason;
    }

    public String claimFinalization(int currentStep) {
        return claimFinalization(this, currentStep);
    }

    public void completeFinalization(Object owner, boolean succeeded) {
        Object key = owner == null ? this : owner;
        synchronized (finalizationLock) {
            finalizationResults.put(key, succeeded);
        }
    }

    public void completeFinalization(boolean succeeded) {
        completeFinalization(this, succeeded);
    }

    public void markHardExceeded(String reason) {
        hardExceededReason.compareAndSet(null, reason == null ? "unknown" : reason);
    }

    public String exceededReason() {
        return hardExceededReason.get();
    }

    public boolean isFinalizationStarted() {
        return finalizationStarted.get();
    }

    public boolean isFinalizationSucceeded() {
        synchronized (finalizationLock) {
            return finalizationOwners.size() == finalizationResults.size()
                    && !finalizationResults.isEmpty()
                    && finalizationResults.values().stream().allMatch(Boolean.TRUE::equals);
        }
    }

    public String getFinalizationReason() {
        return finalizationReason.get();
    }

    public int getTotalTokens() {
        return totalTokens.get();
    }

    public int getToolCalls() {
        return toolCalls.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("softLimitPercent", softRatio);
        snapshot.put("softTokenLimit", maxTotalTokens == null ? null : softTokenLimit());
        snapshot.put("softToolCallLimit", maxToolCalls == null ? null : softToolLimit());
        snapshot.put("softWallMs", maxWallMs == null ? null : softWallLimit());
        snapshot.put("totalTokens", totalTokens.get());
        snapshot.put("toolCalls", toolCalls.get());
        snapshot.put("wallMs", elapsedMs());
        snapshot.put("finalizationTriggered", finalizationStarted.get());
        synchronized (finalizationLock) {
            snapshot.put("finalizationCount", finalizationOwners.size());
        }
        snapshot.put("finalizationReason", finalizationReason.get());
        snapshot.put("finalizationSucceeded", isFinalizationSucceeded());
        snapshot.put(
                "tokensAtFinalization",
                tokensAtFinalization.get() < 0 ? null : tokensAtFinalization.get()
        );
        snapshot.put("exceeded", hardExceededReason.get());
        return snapshot;
    }

    private String finalizationReason(int currentStep) {
        String hard = hardExceededReason.get();
        if (hard != null) {
            return "hard_" + hard;
        }
        if (softStopReason.get() != null) {
            return softStopReason.get();
        }
        if (maxTotalTokens != null && totalTokens.get() >= softTokenLimit()) {
            return "token_reserve";
        }
        if (maxToolCalls != null && toolCalls.get() >= softToolLimit()) {
            return "tool_reserve";
        }
        if (maxWallMs != null && elapsedMs() >= softWallLimit()) {
            return "wall_time_reserve";
        }
        if (maxSteps != null && currentStep >= Math.max(0, maxSteps - 1)) {
            return "step_reserve";
        }
        return null;
    }

    private ToolDecision denied(boolean hardLimit, String reason, String message) {
        return new ToolDecision(false, toolCalls.get(), hardLimit, reason, message);
    }

    private String hardStopMessage() {
        return "工具未执行：评测预算已耗尽。停止探索，基于已有证据输出最终答案。";
    }

    private String softStopMessage() {
        return "工具未执行：评测预算已进入最后收尾阶段。停止探索，基于已有证据输出最终答案。";
    }

    private int softTokenLimit() {
        return (int) Math.ceil(maxTotalTokens * softRatio);
    }

    private int softToolLimit() {
        return Math.max(1, (int) Math.ceil(maxToolCalls * softRatio));
    }

    private long softWallLimit() {
        return (long) Math.ceil(maxWallMs * softRatio);
    }

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
