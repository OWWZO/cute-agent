package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.cute.po.UserQuestionPO;

import java.util.List;

@Mapper
public interface IUserQuestionDao {

    /** 插入提问；违反开放问题唯一索引时由 Spring 抛出 DuplicateKeyException。 */
    int insert(UserQuestionPO row);

    /** 按 questionId 查询未删除记录；SQL 使用 LIMIT 1。 */
    UserQuestionPO selectByQuestionId(@Param("questionId") String questionId);

    /** 按 resumeRequestId 查询未删除记录；SQL 使用 LIMIT 1。 */
    UserQuestionPO selectByResumeRequestId(@Param("resumeRequestId") String resumeRequestId);

    /**
     * 查询开放列表。当前 SQL 使用 PENDING/RESUME_PENDING/RESUMING/ANSWERED，
     * 与 {@link #countOpenBySessionId(String)} 的状态集合不同。
     */
    List<UserQuestionPO> selectOpenBySessionId(@Param("sessionId") String sessionId);

    /** 统计开放记录。当前 SQL 使用 PENDING/RESUME_PENDING/RESUMING，不包含 ANSWERED。 */
    int countOpenBySessionId(@Param("sessionId") String sessionId);

    /** CAS PENDING → RESUME_PENDING；返回实际更新行数，0 表示 CAS 失败。 */
    int casAnswerPending(@Param("questionId") String questionId,
                         @Param("userId") String userId,
                         @Param("answersJson") String answersJson,
                         @Param("resumeRequestId") String resumeRequestId);

    /** CAS RESUME_PENDING → RESUMING；返回实际更新行数，0 表示 CAS 失败。 */
    int casClaimResume(@Param("resumeRequestId") String resumeRequestId,
                       @Param("userId") String userId);

    /** 从 RESUME_PENDING/RESUMING 写入指定 status；返回实际更新行数。 */
    int markStatus(@Param("questionId") String questionId, @Param("status") String status);

    /** 从给定状态集合 CAS 到 CANCELLED；返回实际更新行数。 */
    int casCancel(@Param("questionId") String questionId,
                  @Param("userId") String userId,
                  @Param("fromStatuses") List<String> fromStatuses);
}
