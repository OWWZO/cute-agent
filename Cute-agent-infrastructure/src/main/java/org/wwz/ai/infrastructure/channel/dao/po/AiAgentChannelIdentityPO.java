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

    private String platformUserId;

    private String userId;

    private String displayName;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
