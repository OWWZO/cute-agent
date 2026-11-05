package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;

import java.util.List;

/**
 * 时间线目录表 DAO。
 */
@Mapper
public interface ITimelineItemDao {

    int upsert(TimelineItem item);

    int upsertBatch(@Param("items") List<TimelineItem> items);

    List<TimelineItem> queryByRunIds(@Param("runIds") List<String> runIds);
}
