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

    private String coverUrl;

    private Integer sortOrder;

    /** 发布状态：{@code ONLINE} / {@code OFFLINE}。 */
    private String status;

    private LocalDateTime publishedAt;

    private LocalDateTime updatedAt;

    /**
     * 标签以只读副本返回，避免调用方通过 getter 改动视图内部状态。
     */
    public List<String> getTags() {
        return tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
    }
}
