package org.wwz.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.UserAuthSessionPO;

import java.time.LocalDateTime;

/**
 * user_auth_session data access object.
 */
@Mapper
public interface IUserAuthSessionDao {

    int insert(UserAuthSessionPO session);

    UserAuthSessionPO queryBySessionId(@Param("sessionId") String sessionId);

    UserAuthSessionPO queryByRefreshTokenHash(@Param("refreshTokenHash") String refreshTokenHash);

    /**
     * 原子轮换 refresh token。
     *
     * <p>仅当 sessionId、currentRefreshTokenHash 匹配，会话未撤销且未过期时更新；
     * 返回 1 表示轮换成功，返回 0 表示 CAS 失败。</p>
     */
    int rotate(@Param("sessionId") String sessionId,
               @Param("currentRefreshTokenHash") String currentRefreshTokenHash,
               @Param("nextRefreshTokenHash") String nextRefreshTokenHash,
               @Param("lastSeenAt") LocalDateTime lastSeenAt,
               @Param("expiresAt") LocalDateTime expiresAt);

    int revokeByRefreshTokenHash(@Param("refreshTokenHash") String refreshTokenHash,
                                 @Param("revokedAt") LocalDateTime revokedAt);

    int revokeByUserId(@Param("userId") String userId,
                       @Param("revokedAt") LocalDateTime revokedAt);

    int revokeByUserIdExcept(@Param("userId") String userId,
                             @Param("retainedSessionId") String retainedSessionId,
                             @Param("revokedAt") LocalDateTime revokedAt);
}
