package org.wwz.ai.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.infrastructure.dao.cute.ITimelineItemDao;

import java.util.List;

/**
 * 时间线目录仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class TimelineItemRepository implements ITimelineItemRepository {

    private final ITimelineItemDao timelineItemDao;

    @Override
    public int upsert(TimelineItem item) {
        return timelineItemDao.upsert(item);
    }

    @Override
    public int upsertBatch(List<TimelineItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return timelineItemDao.upsertBatch(items);
    }

    @Override
    public TimelineItem queryBySessionAndItemId(String sessionId, String itemId) {
        if (sessionId == null || sessionId.isBlank()
                || itemId == null || itemId.isBlank()) {
            return null;
        }
        return timelineItemDao.queryBySessionAndItemId(sessionId, itemId);
    }

    @Override
    public List<TimelineItem> queryByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        List<TimelineItem> items = timelineItemDao.queryByRunIds(runIds);
        return items == null ? List.of() : items;
    }
}
