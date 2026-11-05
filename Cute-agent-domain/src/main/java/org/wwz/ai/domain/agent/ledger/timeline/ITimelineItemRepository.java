package org.wwz.ai.domain.agent.ledger.timeline;

import java.util.List;

/**
 * 时间线目录仓储端口。目录可由 Ledger 重建，不是第二执行事实源。
 */
public interface ITimelineItemRepository {

    int upsert(TimelineItem item);

    int upsertBatch(List<TimelineItem> items);

    /**
     * 按 run 批量读取目录。入参为空时返回空列表，实现必须使用单条 IN 查询。
     */
    List<TimelineItem> queryByRunIds(List<String> runIds);
}
