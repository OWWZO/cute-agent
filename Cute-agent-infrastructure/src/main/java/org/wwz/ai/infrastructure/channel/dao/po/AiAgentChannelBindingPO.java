package org.wwz.ai.infrastructure.channel.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_agent_channel_binding 的持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentChannelBindingPO {

    private Long id;

    private String ownerUserId;

    private String platform;

    private String appId;

    /** 明文 AppSecret。禁止打印、禁止出现在响应体。 */
    private String appSecret;

    private String botOpenid;

    private String status;

    private String lastError;

    private LocalDateTime connectedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

    /**
     * 刻意不输出 appSecret：任何一次日志打印都会把凭证写进磁盘。
     * 新增敏感字段时必须同步更新此方法，或改用 {@code @ToString.Exclude} 显式排除。
     */
    @Override
    public String toString() {
        return "AiAgentChannelBindingPO(id=" + id
                + ", ownerUserId=" + ownerUserId
                + ", platform=" + platform
                + ", appId=" + appId
                + ", status=" + status
                + ", connectedAt=" + connectedAt
                + ")";
    }
}
