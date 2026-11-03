package org.wwz.ai.domain.agent.runtime.desktopcontrol;

import java.util.List;
import java.util.Optional;

/**
 * 桌面控制任务仓储端口。
 * <p>常规状态机：{@code PENDING → RESUME_PENDING → RESUMING → COMPLETED}；
 * PENDING / RESUME_PENDING 可被 CAS 取消为 CANCELLED；FAILED 为终态。
 * CAS 方法统一以返回 {@code false} 表示「前置条件不满足」（记录不存在、状态不符或归属不匹配），
 * 不抛出业务异常。</p>
 */
public interface IDesktopControlRepository {

    void insert(DesktopControlRecord record);

    Optional<DesktopControlRecord> findByControlId(String controlId);

    Optional<DesktopControlRecord> findByResumeRequestId(String resumeRequestId);

    List<DesktopControlRecord> listOpenBySessionId(String sessionId);

    boolean hasOpenBySessionId(String sessionId);

    /**
     * CAS：把 {@code PENDING} 推进到 {@code RESUME_PENDING}，并记录发起的 resumeRequestId。
     * <p>返回 {@code true} 仅表示 CAS 成功；记录不存在、已非 PENDING 或归属不匹配都返回 {@code false}。
     * 终态完成走 {@link #markCompleted(String, String)}，不是本方法。</p>
     */
    boolean casResumePending(String controlId, String userId, String resumeRequestId);

    /**
     * CAS：把 {@code RESUME_PENDING} 推进到 {@code RESUMING}，按 resumeRequestId 定位记录并校验归属。
     * <p>返回 {@code false} 表示记录不存在、当前不是 RESUME_PENDING（含已被认领）、归属不匹配或并发落败；
     * 不区分具体原因，调用方需自行查询记录状态。</p>
     */
    boolean casClaimResume(String resumeRequestId, String userId);

    /**
     * CAS：把 {@code RESUMING} 推进到 {@code COMPLETED}，并在同一条 UPDATE 中校验记录归属。
     * <p>只有 {@code userId} 与记录的 {@code user_id} 一致（或记录未绑定用户）时才会更新；
     * 归属不匹配返回 {@code false}，防止拿到 controlId 的调用方改写他人记录。</p>
     */
    boolean markCompleted(String controlId, String userId);

    /**
     * 直接改写状态列。
     * <p>{@code status} 必须是 {@link DesktopControlStatuses} 中已登记的值；调用方应使用常量，
     * 不要传任意字符串。该方法是低层逃生口：常规流转优先使用
     * {@link #casResumePending} / {@link #casClaimResume} / {@link #markCompleted(String, String)} / {@link #casCancel}，
     * 它们带有状态守卫。</p>
     */
    boolean markStatus(String controlId, String status);

    /**
     * CAS：把 {@link DesktopControlStatuses#CANCELABLE} 中的状态（PENDING / RESUME_PENDING）推进到
     * {@code CANCELLED}，并校验归属。
     * <p>返回 {@code false} 表示记录不存在、当前状态不可取消（RESUMING 及终态）或归属不匹配。</p>
     */
    boolean casCancel(String controlId, String userId);
}
