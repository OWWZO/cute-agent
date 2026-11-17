package org.wwz.ai.application.agent.query;

import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.List;

/**
 * 单个 run 的 canonical 时间线快照。
 */
public record RunTimelineSnapshot(
        String requestId,
        String sessionId,
        String status,
        boolean terminal,
        long watermark,
        List<TimelineItemView> items
) {

    public RunTimelineSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
