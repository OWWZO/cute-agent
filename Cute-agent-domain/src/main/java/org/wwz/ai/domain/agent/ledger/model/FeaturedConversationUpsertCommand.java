package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 标签以只读副本返回，避免调用方在命令交给仓储后继续改动其状态。
     */
    public List<String> getTags() {
        return tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
    }
}
