package org.wwz.ai.domain.agent.runtime.askuser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AskUserQuestion 交互附属状态仓储。
 * <p>「同一 session 同时只允许一个待回答的问题」由 {@link #insertIfAbsent} 在数据库侧判定：
 * 判重与写入发生在同一条条件插入语句里，不存在 {@code hasOpenBySessionId} + 无条件 {@code insert}
 * 的 check-then-act 窗口。占用 session 的判定条件是已存在 PENDING / RESUME_PENDING 记录；
 * RESUMING 表示该问题已被续跑认领，允许其 continuation 再提出下一个问题。</p>
 */
public interface IUserQuestionRepository {

    /**
     * 条件插入：仅当 session 没有待回答（PENDING / RESUME_PENDING）记录时写入。
     *
     * @return true=已写入；false=session 已有待回答的问题，或 record 为空
     */
    boolean insertIfAbsent(UserQuestionRecord record);

    Optional<UserQuestionRecord> findByQuestionId(String questionId);

    Optional<UserQuestionRecord> findByResumeRequestId(String resumeRequestId);

    List<UserQuestionRecord> listOpenBySessionId(String sessionId);

    /** 存在性探测；与 {@link #insertIfAbsent} 之间没有原子性，见类注释。 */
    boolean hasOpenBySessionId(String sessionId);

    /**
     * CAS PENDING → RESUME_PENDING；成功返回 true。
     */
    boolean casAnswerPending(String questionId, String userId, Map<String, String> answers, String resumeRequestId);

    /**
     * CAS RESUME_PENDING → RESUMING；成功返回 true。
     */
    boolean casClaimResume(String resumeRequestId, String userId);

    /**
     * 续跑收口用的受限状态写入：只允许从 RESUME_PENDING / RESUMING 迁出，
     * 目标状态必须是 {@link UserQuestionStatuses#RESUME_OUTCOMES} 之一；其它源状态或目标状态不会写入，
     * 避免破坏 {@code PENDING → RESUME_PENDING → RESUMING} 顺序与拼错的状态值。
     */
    boolean markStatus(String questionId, String status);

    boolean casCancel(String questionId, String userId);
}
