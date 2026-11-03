package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 精品对话管理查询条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedConversationQueryCondition {

    /**
     * 发布状态过滤，允许值见精品对话仓储的 ONLINE/OFFLINE 常量；
     * {@code null} 或空白表示不按状态过滤。
     */
    private String status;

    private String sessionId;

    private String title;

    private int offset;

    private int limit;
}
