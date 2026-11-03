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

    /** 关联会话 id，等值匹配；{@code null} 或空白表示不按会话过滤。 */
    private String sessionId;

    /**
     * 标题模糊匹配（SQL {@code LIKE CONCAT('%', value, '%')}）；
     * {@code null} 或空白表示不按标题过滤。传入值中的 {@code %} / {@code _} 不会被转义，
     * 会作为 LIKE 通配符生效。
     */
    private String title;

    private int offset;

    private int limit;
}
