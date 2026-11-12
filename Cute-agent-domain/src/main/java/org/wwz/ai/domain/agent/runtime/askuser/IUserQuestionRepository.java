package org.wwz.ai.domain.agent.runtime.askuser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AskUserQuestion 交互附属状态仓储。
 * <p>「同一 session 同时只允许一条未删除、同状态的提问」由 {@link #insertIfAbsent} 对应的
 * 数据库唯一索引 {@code uk_user_question_open (session_id, status, deleted)} 判定；第二条并发写入
 * 会拿到 {@code DuplicateKeyException} 并被仓储层转成「该会话已有未关闭的提问」业务错误，
 * 不存在 {@code hasOpenBySessionId} + 无条件 insert 的 check-then-act 窗口。</p>
 */
public interface IUserQuestionRepository {

    /**
     * 插入提问：违反开放问题唯一索引时抛出业务错误。
     *
     * @return true=已写入；false=record 为空
     */
    boolean insertIfAbsent(UserQuestionRecord record);

    /**
     * 按 questionId 查询；null/空白返回 {@link Optional#empty()}，查询前去除首尾空白。
     */
    Optional<UserQuestionRecord> findByQuestionId(String questionId);

    /**
     * 按 resumeRequestId 查询；null/空白返回 {@link Optional#empty()}，查询前去除首尾空白。
     * <p>resumeRequestId 在库中带唯一约束（{@code uk_user_question_resume}），同一 id 至多一条未删除记录。</p>
     */
    Optional<UserQuestionRecord> findByResumeRequestId(String resumeRequestId);

    List<UserQuestionRecord> listOpenBySessionId(String sessionId);

    /** 存在性探测；与 {@link #insertIfAbsent} 之间没有原子性，见类注释。 */
    boolean hasOpenBySessionId(String sessionId);

    /**
     * CAS PENDING → RESUME_PENDING；成功返回 true。
     * <p>questionId 或 resumeRequestId 为 null/空白时直接返回 false；{@code answers} 为 null 时按空 map 持久化。
     * {@code userId} 为 null/空白时不校验归属（可命中任意用户或未绑定用户的记录）。实现必须在调用时
     * 完成序列化/复制，调用方事后修改 {@code answers} 不得影响已持久化内容。</p>
     */
    boolean casAnswerPending(String questionId, String userId, Map<String, String> answers, String resumeRequestId);

    /**
     * CAS RESUME_PENDING → RESUMING；成功返回 true。
     * <p>返回 false 不区分以下原因：resumeRequestId 为 null/空白、记录不存在、当前状态不是 RESUME_PENDING
     * （包括已被认领进入 RESUMING）、归属不匹配、并发认领落败。本方法没有「已在 RESUMING 即幂等成功」的语义，
     * 调用方需要区分时必须先查询记录状态。</p>
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
