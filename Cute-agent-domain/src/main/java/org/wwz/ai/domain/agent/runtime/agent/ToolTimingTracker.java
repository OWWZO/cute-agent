package org.wwz.ai.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用墙钟计时：从命令登记开始，到 outcome 收口为止。
 * <p>
 * 计时只在一次工具批次内有效，收口后立即移除，避免长会话里堆积。
 */
final class ToolTimingTracker {

    private final Map<String, ReplayTiming> runtimeTimings = new ConcurrentHashMap<>();

    void start(List<ToolCall> commands) {
        if (commands == null) {
            return;
        }
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())) {
                continue;
            }
            runtimeTimings.computeIfAbsent(command.getId(), ignored -> ReplayTiming.startRuntime());
        }
    }

    /**
     * 取计时起点；缺失时补建，保证非空白 toolCallId 的调用方总能拿到非 null。
     * <p>直接返回 computeIfAbsent 的结果：先 start 再 get 的两步查找会被并发 clear 打断，
     * 出现「刚补建就被移除」导致返回 null。</p>
     */
    ReplayTiming resolve(ToolCall command) {
        if (command == null || StringUtils.isBlank(command.getId())) {
            return null;
        }
        return runtimeTimings.computeIfAbsent(command.getId(), ignored -> ReplayTiming.startRuntime());
    }

    /** 把收口时间写回计时并挂到 outcome 上。 */
    void attachFinished(ToolCall command, ToolExecutionOutcome outcome) {
        if (outcome == null) {
            return;
        }
        ReplayTiming timing = resolve(command);
        if (timing == null) {
            return;
        }
        outcome.setTiming(ReplayTiming.finishRuntime(timing));
    }

    void clear(ToolCall command) {
        if (command != null && StringUtils.isNotBlank(command.getId())) {
            runtimeTimings.remove(command.getId());
        }
    }
}
