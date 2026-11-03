package org.wwz.ai.domain.agent.channel.model;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 一个 IM 机器人绑定。
 * <p>{@code appSecret} 是明文凭证，只在建立连接与换取 access_token 时读取，
 * <b>不得出现在日志、响应体或 toString 中</b>。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelBinding {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String PLATFORM_QQBOT = "qqbot";

    private Long id;

    private String ownerUserId;

    private String platform;

    private String appId;

    /** 明文 AppSecret。禁止打印、序列化，也不参与对象身份判定。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JSONField(serialize = false)
    private String appSecret;

    private String botOpenid;

    private String status;

    private String lastError;

    private LocalDateTime connectedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 只有 ACTIVE / ERROR 可以重试；PENDING 正在连、DISABLED 已解绑。 */
    public boolean canRetry() {
        return STATUS_ERROR.equals(status);
    }

    /** 刻意不输出 appSecret。 */
    @Override
    public String toString() {
        return "ChannelBinding(id=" + id
                + ", ownerUserId=" + ownerUserId
                + ", platform=" + platform
                + ", appId=" + appId
                + ", status=" + status
                + ", connectedAt=" + connectedAt
                + ")";
    }
}
