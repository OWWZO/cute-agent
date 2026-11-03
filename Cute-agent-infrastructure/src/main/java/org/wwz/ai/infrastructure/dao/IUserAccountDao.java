package org.wwz.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.UserAccountPO;

/**
 * user_account data access object.
 */
@Mapper
public interface IUserAccountDao {

    int insert(UserAccountPO account);

    /** 按主键查询未软删的账户。 */
    UserAccountPO queryById(@Param("id") Long id);

    /** 按 userId 查询未软删的账户；调用方依赖 user_id 的逻辑唯一性。 */
    UserAccountPO queryByUserId(@Param("userId") String userId);

    /** 按 loginName 查询未软删的账户；调用方依赖 login_name 的逻辑唯一性。 */
    UserAccountPO queryByLoginName(@Param("loginName") String loginName);

    int updatePassword(@Param("userId") String userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("updateTime") java.time.LocalDateTime updateTime);

    int updateLastLoginAt(@Param("userId") String userId,
                          @Param("lastLoginAt") java.time.LocalDateTime lastLoginAt);

}
