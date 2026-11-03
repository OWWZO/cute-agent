package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.model.FeaturedConversationQueryCondition;
import org.wwz.ai.infrastructure.dao.po.FeaturedConversationPO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 精品对话 DAO。
 */
@Mapper
public interface IFeaturedConversationDao {

    int upsert(FeaturedConversationPO po);

    /** 按 featuredId 查询未删除记录；SQL 使用 LIMIT 1。 */
    FeaturedConversationPO queryByFeaturedId(@Param("featuredId") String featuredId);

    /** 按 sessionId 查询未删除记录；SQL 使用 LIMIT 1。 */
    FeaturedConversationPO queryBySessionId(@Param("sessionId") String sessionId);

    /**
     * 分页查询 ONLINE 记录，按 {@code sort_order DESC, id DESC} 稳定排序。
     * 调用方负责传入合法的非负 offset 与正数 limit。
     */
    List<FeaturedConversationPO> queryOnlineList(@Param("offset") int offset, @Param("limit") int limit);

    Integer countOnline();

    /**
     * 按 featuredId 更新状态。当前 SQL 总是覆盖 published_at（包括写入 null），
     * 且只匹配未删除记录。
     */
    int updateStatus(@Param("featuredId") String featuredId,
                     @Param("status") String status,
                     @Param("updatedBy") String updatedBy,
                     @Param("publishedAt") LocalDateTime publishedAt,
                     @Param("updatedAt") LocalDateTime updatedAt);

    List<FeaturedConversationPO> queryAdminList(FeaturedConversationQueryCondition condition);

    Integer countAdminList(FeaturedConversationQueryCondition condition);
}
