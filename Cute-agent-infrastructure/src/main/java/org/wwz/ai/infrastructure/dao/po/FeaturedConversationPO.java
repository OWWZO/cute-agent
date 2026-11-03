package org.wwz.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 精品对话持久化对象。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedConversationPO {

    private Long id;

    private String featuredId;

    private String sessionId;

    private String title;

    private String summary;

    private String coverResourceKey;

    private String coverUrl;

    private String tagsJson;

    private Integer sortOrder;

    private String status;

    private String publishedBy;

    /** 业务发布时间，由仓储的发布/上下线操作写入。 */
    private LocalDateTime publishedAt;

    private String updatedBy;

    /** 业务更新时间，由仓储的写入/发布/上下线操作写入。 */
    private LocalDateTime updatedAt;

    /** 数据库审计创建时间；应用写入语句不显式维护该字段。 */
    private LocalDateTime createTime;

    /** 数据库审计更新时间；应用写入语句不显式维护该字段。 */
    private LocalDateTime updateTime;

    private Integer deleted;
}
