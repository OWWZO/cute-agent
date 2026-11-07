package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.cute.po.SessionInboxPO;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ISessionInboxDao {

    int insertIfAbsent(SessionInboxPO row);

    SessionInboxPO selectByKey(@Param("sessionId") String sessionId,
                               @Param("idempotencyKey") String idempotencyKey);

    List<SessionInboxPO> selectClaimableForUpdate(@Param("sessionId") String sessionId,
                                                  @Param("kind") String kind,
                                                  @Param("runId") String runId,
                                                  @Param("nowMs") long nowMs,
                                                  @Param("limit") int limit);

    int markClaimed(@Param("ids") Collection<Long> ids,
                    @Param("runId") String runId,
                    @Param("leaseUntilMs") long leaseUntilMs,
                    @Param("nowMs") long nowMs);

    int ackDelivered(@Param("ids") Collection<Long> ids,
                     @Param("runId") String runId,
                     @Param("deliveredAtMs") long deliveredAtMs);

    int releaseClaims(@Param("sessionId") String sessionId,
                      @Param("runId") String runId);

    int holdPendingUserMessages(@Param("sessionId") String sessionId);

    int releaseHeldUserMessages(@Param("sessionId") String sessionId,
                                @Param("idempotencyKey") String idempotencyKey);

    int countOutstanding(@Param("sessionId") String sessionId);

    int countDeliverableKind(@Param("sessionId") String sessionId,
                             @Param("kind") String kind,
                             @Param("nowMs") long nowMs);

    int countHeldUserMessages(@Param("sessionId") String sessionId);

    SessionInboxPO selectFirstDeliverableUser(@Param("sessionId") String sessionId,
                                              @Param("nowMs") long nowMs);
}
