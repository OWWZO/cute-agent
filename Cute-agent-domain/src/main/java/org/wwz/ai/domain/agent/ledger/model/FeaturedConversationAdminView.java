package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 精品对话管理列表项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedConversationAdminView {

    private String featuredId;

    private String sessionId;

    private String title;

    private String summary;

    private List<String> tags;

    /** 封面地址；{@code null} 表示未配置或历史行未记录。 */
    private String coverUrl;

    /** 排序值，越大越靠前；写入时 {@code null} 归一化为 0，历史行可能为 {@code null}。 */
    private Integer sortOrder;

    /** 发布状态：{@code ONLINE} / {@code OFFLINE}。 */
    private String status;

    /** 首次发布时间；草稿或从未发布的记录为 {@code null}。 */
    private LocalDateTime publishedAt;

    /** 最近更新时间；新建/更新时写入，历史行可能为 {@code null}。 */
    private LocalDateTime updatedAt;

    /**
     * 标签以只读副本返回，避免调用方通过 getter 改动视图内部状态。
     */
    public List<String> getTags() {
        return tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
    }
}
