package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 精品对话新增或更新命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedConversationUpsertCommand {

    /** 主键，必填；为空时仓储 upsert 直接返回 false 且不写库。 */
    private String featuredId;

    /** 关联会话 id，必填；为空时仓储 upsert 直接返回 false 且不写库。 */
    private String sessionId;

    private String title;

    private String summary;

    private String coverResourceKey;

    private String coverUrl;

    private List<String> tags;

    private Integer sortOrder;

    private String operator;
}
