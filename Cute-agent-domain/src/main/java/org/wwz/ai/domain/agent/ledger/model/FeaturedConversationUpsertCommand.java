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
 * <p>新增和更新共用同一个类型：{@code featuredId} 与 {@code sessionId} 均为必填，
 * 任一为空白时仓储直接返回 {@code false} 且不写库。仓储先按 {@code featuredId} 查找已有记录，
 * 找不到再按 {@code sessionId} 查找：命中则更新（保留已有 status / publishedBy / publishedAt），
 * 未命中才插入（status 默认 {@code OFFLINE}）。因此更新已有记录时必须传对它的
 * {@code featuredId}，不能依赖「不传即新增」。</p>
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
