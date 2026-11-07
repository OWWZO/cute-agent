package org.wwz.ai.domain.agent.ledger.model.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 单个回放事件的时间信息。durationMs 表示一次 invocation 的耗时，不代表步骤组墙钟耗时。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayTiming implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String SOURCE_LEDGER = "ledger";
    public static final String SOURCE_RUNTIME = "runtime";
    public static final String SOURCE_INFERRED = "inferred";

    /**
     * 计时字段用 volatile：{@code finishRuntime} 会按「durationMs/source 先写、finishedAt 后写」
     * 的顺序发布，保证读端一旦看到 finishedAt 非空，就一定能看到本次收口的 duration/source。
     * <p>调用方仍不得在多个线程间随意共享可变实例；需要快照请用 {@link #copyOf}。</p>
     */
    private volatile LocalDateTime startedAt;
    private volatile LocalDateTime firstTokenAt;
    private volatile LocalDateTime finishedAt;
    private volatile Long durationMs;
    private volatile String source;

    /** 运行时计时起点。 */
    public static ReplayTiming startRuntime() {
        return ReplayTiming.builder()
                .startedAt(LocalDateTime.now())
                .source(SOURCE_RUNTIME)
                .build();
    }

    /**
     * 收口运行时计时：已收口或未开始则原样返回，避免重复覆盖既有耗时。
     * <p>先写 durationMs/source，最后写 finishedAt 作为发布标记，避免并发读端看到
     * 「已结束但没有耗时」的半成品。</p>
     */
    public static ReplayTiming finishRuntime(ReplayTiming timing) {
        if (timing == null || timing.getFinishedAt() != null || timing.getStartedAt() == null) {
            return timing;
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        timing.setDurationMs(Math.max(0L, Duration.between(timing.getStartedAt(), finishedAt).toMillis()));
        timing.setSource(SOURCE_RUNTIME);
        timing.setFinishedAt(finishedAt);
        return timing;
    }

    /**
     * 记录流式响应中首个有效增量到达时间。
     * <p>只写一次；startedAt 为空或已经记录过时保持原值。</p>
     */
    public synchronized void markFirstToken() {
        if (startedAt == null || firstTokenAt != null) {
            return;
        }
        firstTokenAt = LocalDateTime.now();
    }

    /** 复制一份独立计时，避免下游改动污染原对象。 */
    public static ReplayTiming copyOf(ReplayTiming timing) {
        if (timing == null) {
            return null;
        }
        return ReplayTiming.builder()
                .startedAt(timing.getStartedAt())
                .firstTokenAt(timing.getFirstTokenAt())
                .finishedAt(timing.getFinishedAt())
                .durationMs(timing.getDurationMs())
                .source(timing.getSource())
                .build();
    }
}
