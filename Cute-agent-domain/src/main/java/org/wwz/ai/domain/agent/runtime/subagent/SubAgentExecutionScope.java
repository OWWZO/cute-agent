package org.wwz.ai.domain.agent.runtime.subagent;

import java.util.function.Supplier;

/**
 * 标记当前线程正在原地执行 Agent 工具。
 *
 * <p>正常 Agent 工具占用 task 池线程；task 池拒绝时由调用线程原地执行。
 * 该标记只用于可观测性，不参与并发许可判定。</p>
 */
public final class SubAgentExecutionScope {

    private static final ThreadLocal<Integer> FOREGROUND_DEPTH = ThreadLocal.withInitial(() -> 0);

    private SubAgentExecutionScope() {
    }

    public static <T> T runInline(Supplier<T> action) {
        int depth = FOREGROUND_DEPTH.get();
        FOREGROUND_DEPTH.set(depth + 1);
        try {
            return action.get();
        } finally {
            if (depth == 0) {
                FOREGROUND_DEPTH.remove();
            } else {
                FOREGROUND_DEPTH.set(depth);
            }
        }
    }

    public static boolean isInline() {
        return FOREGROUND_DEPTH.get() > 0;
    }
}
