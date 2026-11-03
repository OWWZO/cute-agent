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

    FeaturedConversation queryByFeaturedId(String featuredId);

    FeaturedConversation queryBySessionId(String sessionId);

    List<FeaturedConversation> queryOnlineList(int offset, int limit);

    int countOnline();

    FeaturedConversationPageResult<FeaturedConversationAdminView> queryAdminList(
            FeaturedConversationQueryCondition condition
    );

    /**
     * 新建或更新精品对话。
     *
     * @return {@code true} 表示写入成功（新建或更新）；{@code false} 表示参数校验失败
     *         （命令为 null，或 {@code featuredId}/{@code sessionId} 为空），此时未写库。
     *         {@code featuredId} 是更新与查询的主键，必须非空。
     */
    boolean upsert(FeaturedConversationUpsertCommand command);

    boolean updateStatus(String featuredId, String status, String operator);
}
