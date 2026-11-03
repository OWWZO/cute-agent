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
 * 精品对话公共详情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedConversationPublicDetail {

    private String featuredId;

    private String sessionId;

    private String title;

    private String summary;

    private String coverUrl;

    private List<String> tags;

    private String status;

    private LocalDateTime publishedAt;

    private LocalDateTime contentLastActiveAt;

    private Boolean contentAvailable;

    private String contentUnavailableReason;

    /**
     * 新的默认轻量 history 载荷；旧字段保留给非历史兼容调用方。
     * <p>与 {@link #historyDetail} 互斥：默认路径只填 {@code historyPage}，
     * 只有显式请求完整历史时才填 {@code historyDetail}。</p>
     */
    private ConversationHistoryPage historyPage;

    private ConversationHistoryDetail historyDetail;

    /**
     * 标签以只读副本返回，避免调用方通过 getter 改动公共详情载荷。
     */
    public List<String> getTags() {
        return tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
    }
}
