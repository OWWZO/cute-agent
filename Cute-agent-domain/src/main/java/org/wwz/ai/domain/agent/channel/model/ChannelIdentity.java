package org.wwz.ai.domain.agent.channel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * QQ 用户到内部身份的映射，按 binding 隔离。
 * <p>{@code bindingId} + {@code platform} + {@code platformUserId} 共同构成唯一身份键
 * （DB 唯一索引 {@code uk_channel_identity}），三者必须非空，否则查找会静默不命中。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelIdentity {

    private Long id;

    private Long bindingId;

    private String platform;

    private String platformUserId;

    /** 内部 user_id，形如 {@code ch_42_a1b2c3d4e5f6g7h8}。 */
    private String userId;

    private String displayName;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;
}
