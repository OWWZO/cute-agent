package org.wwz.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Persistence object for user_account.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserAccountPO {

    private Long id;

    /** 稳定业务用户 ID，供 JWT sub 与业务归属使用。 */
    private String userId;

    @ToString.Exclude
    private String tokenDigest;

    private Integer status;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    @ToString.Exclude
    private String lastIp;

    @ToString.Exclude
    private String lastUserAgent;

    /** 历史展示名/兼容字段，不作为当前登录或归属判定键。 */
    private String username;

    /** 审计创建时间；仓储 save 时为空会补当前时间。 */
    private LocalDateTime createTime;

    /** 审计更新时间；仓储在保存、改密和记录登录时间时维护。 */
    private LocalDateTime updateTime;

    private Integer deleted;

    /** 规范化登录名（trim + lower-case），当前登录查询使用的唯一业务键。 */
    private String loginName;

    @ToString.Exclude
    private String passwordHash;

    private String nickname;

    private String role;

    /** 最近一次成功登录时间，由登录链路显式更新。 */
    private LocalDateTime lastLoginAt;
}
