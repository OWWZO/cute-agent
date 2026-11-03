package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.cute.po.PlanApprovalPO;

import java.util.List;

@Mapper
public interface IPlanApprovalDao {

    /** 插入审批记录，并回填自增主键。 */
    int insert(PlanApprovalPO row);

    /** 按 approvalId 查询未删除记录；SQL 使用 LIMIT 1。 */
    PlanApprovalPO selectByApprovalId(@Param("approvalId") String approvalId);

    /** 按 resumeRequestId 查询未删除记录；SQL 使用 LIMIT 1。 */
    PlanApprovalPO selectByResumeRequestId(@Param("resumeRequestId") String resumeRequestId);

    /**
     * 查询开放列表。当前 SQL 使用 PENDING/RESUME_PENDING/RESUMING/ANSWERED，
     * 与 {@link #countOpenBySessionId(String)} 的状态集合不同。
     */
    List<PlanApprovalPO> selectOpenBySessionId(@Param("sessionId") String sessionId);

    /** 统计开放记录。当前 SQL 使用 PENDING/RESUME_PENDING/RESUMING，不包含 ANSWERED。 */
    int countOpenBySessionId(@Param("sessionId") String sessionId);

    /** CAS PENDING → RESUME_PENDING；返回实际更新行数。 */
    int casDecidePending(@Param("approvalId") String approvalId,
                         @Param("userId") String userId,
                         @Param("decisionJson") String decisionJson,
                         @Param("resumeRequestId") String resumeRequestId);

    /** CAS RESUME_PENDING → RESUMING；返回实际更新行数。 */
    int casClaimResume(@Param("resumeRequestId") String resumeRequestId,
                       @Param("userId") String userId);

    /** CAS RESUMING → ANSWERED；返回实际更新行数。 */
    int markAnswered(@Param("approvalId") String approvalId);

    /** 写入调用方指定的 status；返回实际更新行数。 */
    int markFailed(@Param("approvalId") String approvalId, @Param("status") String status);

    /** 从给定状态集合 CAS 到 CANCELLED；返回实际更新行数。 */
    int casCancel(@Param("approvalId") String approvalId,
                  @Param("userId") String userId,
                  @Param("fromStatuses") List<String> fromStatuses);

    int cancelBySourceRequestId(@Param("sourceRequestId") String sourceRequestId,
                                @Param("fromStatuses") List<String> fromStatuses);
}
