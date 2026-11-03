package org.wwz.ai.domain.agent.runtime.askuser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AskUserQuestion 交互附属状态仓储。
 * <p>「同一 session 同时只允许一个开放问题」不是本端口能保证的不变量：
 * {@link #hasOpenBySessionId} 只是一次存在性探测，{@link #insert} 是无条件写入且不返回结果，
 * 探针 + 写入的组合是典型的 check-then-act，并发提交同一 session 时会产生两个开放问题。
 * 该不变量目前由调用方按 session 串行提交来保证；本端口不做原子插入。
 * {@link #insert} 在 question_id 唯一键冲突时由底层 DAO 抛出异常，调用方无法从返回值感知。</p>
 */
public interface IUserQuestionRepository {

    void insert(UserQuestionRecord record);

    Optional<UserQuestionRecord> findByQuestionId(String questionId);

    Optional<UserQuestionRecord> findByResumeRequestId(String resumeRequestId);

    List<UserQuestionRecord> listOpenBySessionId(String sessionId);

    /** 存在性探测；与 {@link #insert} 之间没有原子性，见类注释。 */
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
     * 无条件置为 ANSWERED；与 {@code markStatus(questionId, UserQuestionStatuses.ANSWERED)} 等价，
     * 保留它只是为了让最常用的终态迁移不必拼状态字符串。
     */
    boolean markAnswered(String questionId);

    /**
     * 无条件写状态（不回源校验当前值），供续跑失败等旁路收口使用。
     * <p>合法状态集合见 {@link UserQuestionStatuses}；正常状态机应走上面的 CAS 方法，
     * 直接调用本方法可把记录推进到任意状态（含倒退），调用方需自行保证不破坏
     * {@code PENDING → RESUME_PENDING → RESUMING} 顺序。</p>
     */
    boolean markStatus(String questionId, String status);

    boolean casCancel(String questionId, String userId);
}
