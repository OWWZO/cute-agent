package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.entity.FeaturedConversation;
import org.wwz.ai.domain.agent.ledger.model.FeaturedConversationAdminView;
import org.wwz.ai.domain.agent.ledger.model.FeaturedConversationPageResult;
import org.wwz.ai.domain.agent.ledger.model.FeaturedConversationQueryCondition;
import org.wwz.ai.domain.agent.ledger.model.FeaturedConversationUpsertCommand;

import java.util.List;

/**
 * 精品对话读写仓储端口。
 */
public interface IFeaturedConversationRepository {

    /** 未命中返回 {@code null}。 */
    FeaturedConversation queryByFeaturedId(String featuredId);

    /** 未命中返回 {@code null}；{@code sessionId} 在精品对话中唯一（同一 session 最多一条）。 */
    FeaturedConversation queryBySessionId(String sessionId);

    /**
     * 在线列表：只含 {@code status=ONLINE} 且未删除的行，按 {@code sort_order DESC, id DESC} 排序。
     */
    List<FeaturedConversation> queryOnlineList(int offset, int limit);

    /** 与 {@link #queryOnlineList} 使用完全相同的过滤条件。 */
    int countOnline();

    /**
     * 管理端分页列表；{@code condition} 为 {@code null} 时按 {@code offset=0,limit=20} 处理。
     */
    FeaturedConversationPageResult<FeaturedConversationAdminView> queryAdminList(
            FeaturedConversationQueryCondition condition
    );

    boolean upsert(FeaturedConversationUpsertCommand command);

    /**
     * 更新状态。状态按大写存储；返回 {@code false} 表示参数为空或记录不存在。
     */
    boolean updateStatus(String featuredId, String status, String operator);
}
