package org.wwz.ai.domain.agent.runtime.planmode;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @deprecated Continuation 后由 {@code ai_agent_plan_approval} + resume SSE 取代；
 * 保留 bean 仅兼容旧测试与取消回退路径，主链路不再 await Future。
 */
@Deprecated
@Component
public class PendingPlanApprovalRegistry {

    public static final long DEFAULT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    private final ConcurrentHashMap<String, PendingPlanApproval> pending = new ConcurrentHashMap<>();

    public PendingPlanApproval create(String sessionId,
                                      String requestId,
                                      String toolCallId,
                                      String planContent,
                                      String planFilePath,
                                      Long timeoutMs) {
        evictExpired();
        String approvalId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        PendingPlanApproval item = PendingPlanApproval.builder()
                .approvalId(approvalId)
                .sessionId(sessionId)
                .requestId(requestId)
                .toolCallId(toolCallId)
                .planContent(planContent)
                .planFilePath(planFilePath)
                .createdAtMs(System.currentTimeMillis())
                .timeoutMs(timeout)
                .status(PendingPlanApproval.STATUS_PENDING)
                .future(new CompletableFuture<>())
                .build();
        pending.put(approvalId, item);
        return item;
    }

    public Optional<PendingPlanApproval> get(String approvalId) {
        if (StringUtils.isBlank(approvalId)) {
            return Optional.empty();
        }
        evictExpired();
        return Optional.ofNullable(pending.get(approvalId.trim()));
    }

    public List<PendingPlanApproval> listBySession(String sessionId) {
        List<PendingPlanApproval> result = new ArrayList<>();
        if (StringUtils.isBlank(sessionId)) {
            return result;
        }
        evictExpired();
        for (PendingPlanApproval item : pending.values()) {
            if (sessionId.equals(item.getSessionId())
                    && PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                result.add(item);
            }
        }
        return result;
    }

    public boolean approve(String approvalId, String editedPlanContent, String feedback) {
        PendingPlanApproval item = pending.get(StringUtils.trimToEmpty(approvalId));
        if (item == null) {
            return false;
        }
        synchronized (item) {
            if (pending.get(item.getApprovalId()) != item
                    || !PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                return false;
            }
            item.setStatus(PendingPlanApproval.STATUS_APPROVED);
            item.setFeedback(feedback);
            if (StringUtils.isNotBlank(editedPlanContent)) {
                item.setPlanContent(editedPlanContent);
            }
            item.getFuture().complete(PlanApprovalDecision.builder()
                    .approved(true)
                    .feedback(feedback)
                    .editedPlanContent(editedPlanContent)
                    .build());
            pending.remove(item.getApprovalId(), item);
        }
        return true;
    }

    public boolean reject(String approvalId, String feedback) {
        PendingPlanApproval item = pending.get(StringUtils.trimToEmpty(approvalId));
        if (item == null) {
            return false;
        }
        synchronized (item) {
            if (pending.get(item.getApprovalId()) != item
                    || !PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                return false;
            }
            item.setStatus(PendingPlanApproval.STATUS_REJECTED);
            item.setFeedback(feedback);
            item.getFuture().complete(PlanApprovalDecision.builder()
                    .approved(false)
                    .feedback(StringUtils.defaultIfBlank(feedback, "Plan rejected by user"))
                    .build());
            pending.remove(item.getApprovalId(), item);
        }
        return true;
    }

    public boolean cancel(String approvalId, String reason) {
        PendingPlanApproval item = pending.get(StringUtils.trimToEmpty(approvalId));
        if (item == null) {
            return false;
        }
        synchronized (item) {
            if (pending.get(item.getApprovalId()) != item
                    || !PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                return false;
            }
            item.setStatus(PendingPlanApproval.STATUS_CANCELLED);
            item.getFuture().completeExceptionally(
                    new IllegalStateException(StringUtils.defaultIfBlank(reason, "cancelled")));
            pending.remove(item.getApprovalId(), item);
        }
        return true;
    }

    public void cancelByRequestId(String requestId, String reason) {
        if (StringUtils.isBlank(requestId)) {
            return;
        }
        for (PendingPlanApproval item : pending.values()) {
            if (requestId.equals(item.getRequestId())
                    && PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                cancel(item.getApprovalId(), reason);
            }
        }
    }

    public PlanApprovalDecision awaitDecision(PendingPlanApproval item)
            throws TimeoutException, InterruptedException {
        try {
            return item.getFuture().get(item.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (item) {
                if (PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                    item.setStatus(PendingPlanApproval.STATUS_TIMEOUT);
                    item.getFuture().completeExceptionally(e);
                }
            }
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException timeoutException) {
                // 惰性清理可能先于 await 的自身超时把 Future 以 TimeoutException 完成，
                // 对调用方仍保持「超时」这一种异常语义。
                throw timeoutException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } finally {
            pending.remove(item.getApprovalId());
        }
    }

    /**
     * 惰性清理已过期条目：超时只在 awaitDecision 内生效，若请求创建后从未走到 await
     * （异常/中止/只读 get），条目连同完整 planContent 会永久留在单例 map 里。
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        for (PendingPlanApproval item : pending.values()) {
            if (item == null || !PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())) {
                continue;
            }
            if (item.getCreatedAtMs() + item.getTimeoutMs() >= now) {
                continue;
            }
            synchronized (item) {
                if (!PendingPlanApproval.STATUS_PENDING.equals(item.getStatus())
                        || pending.get(item.getApprovalId()) != item) {
                    continue;
                }
                item.setStatus(PendingPlanApproval.STATUS_TIMEOUT);
                item.getFuture().completeExceptionally(new TimeoutException("plan approval expired"));
                pending.remove(item.getApprovalId(), item);
            }
        }
    }

    /** 原子取出兼容 registry 条目，供不再等待 Future 的旧调用方释放引用。 */
    public Optional<PendingPlanApproval> consume(String approvalId) {
        if (StringUtils.isBlank(approvalId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.remove(approvalId.trim()));
    }
}
