package org.wwz.ai.infrastructure.channel.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_agent_channel_identity 的持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentChannelIdentityPO {

    private Long id;

    private Long bindingId;

    private String platform;

    /** 平台侧用户标识，例如 QQ 的 openid。 */
    private String platformUserId;

    /** 内部用户标识，由 {@code (bindingId, platformUserId)} 关联得到。 */
    private String userId;

    private String displayName;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
