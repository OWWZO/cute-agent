package org.wwz.ai.application.agent.channel.binding;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.channel.port.ChannelActivationListener;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway.PollOutcome;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway.Status;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 扫码任务状态机。
 * <p>任务只存在内存：{@code task_id} 是短期凭证，不落库。每个任务每 2 秒轮询一次，
 * 最长 600 秒；二维码过期即置 EXPIRED，由前端重新发起。</p>
 */
@Slf4j
public class ChannelBindingQrService {

    private static final long POLL_INTERVAL_SECONDS = 2;
    private static final long TASK_TTL_SECONDS = 600;

    public record QrView(String taskId, String qrUrl, long expiresInSeconds) {
    }

    public record QrStatus(String status, Long bindingId, String error) {
    }

    private final ChannelQrGateway gateway;
    private final ChannelActivationListener activationListener;
    private final Map<String, QrTask> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public ChannelBindingQrService(ChannelQrGateway gateway, ChannelActivationListener activationListener) {
        this.gateway = gateway;
        this.activationListener = activationListener;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "channel-qr-poller");
            thread.setDaemon(true);
            return thread;
        });
        // 过期任务清理：内存里最多留 10 分钟。
        scheduler.scheduleWithFixedDelay(this::evictStale, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 创建扫码任务。
     * <p>幂等：同一用户已有进行中的任务时直接返回原 taskId，避免前端反复点击产生僵尸轮询。</p>
     */
    public QrView createQr(String ownerUserId) {
        for (Map.Entry<String, QrTask> entry : tasks.entrySet()) {
            QrTask task = entry.getValue();
            if (ownerUserId.equals(task.ownerUserId) && task.isFresh()) {
                return new QrView(entry.getKey(), gateway.buildQrUrl(entry.getKey()), task.remainingSeconds());
            }
        }
        String taskId = gateway.createTask(ownerUserId);
        QrTask task = new QrTask(ownerUserId, Instant.now());
        tasks.put(taskId, task);
        schedule(taskId, task);
        return new QrView(taskId, gateway.buildQrUrl(taskId), task.remainingSeconds());
    }

    public QrStatus poll(String taskId) {
        QrTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalStateException("任务已失效，请重新生成二维码");
        }
        return new QrStatus(task.status.name(), task.bindingId, task.error);
    }

    private void schedule(String taskId, QrTask task) {
        task.future = scheduler.scheduleWithFixedDelay(
                () -> pollOnce(taskId, task),
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollOnce(String taskId, QrTask task) {
        if (task.status != Status.PENDING) {
            cancel(task);
            return;
        }
        if (task.isTimedOut()) {
            log.warn("channel qr task timed out taskId={} owner={}", taskId, task.ownerUserId);
            finish(task, Status.EXPIRED, null, "二维码已过期，请重新生成");
            return;
        }
        try {
            PollOutcome outcome = gateway.poll(taskId);
            if (outcome.status() == Status.COMPLETED) {
                finish(task, Status.COMPLETED, outcome.bindingId(), null);
                log.info("channel qr completed taskId={} bindingId={}", taskId, outcome.bindingId());
                // 起连接是扫码完成的最后一步：没有它 binding 会永远停在 PENDING，bot 不上线。
                if (outcome.bindingId() != null && activationListener != null) {
                    activationListener.onActivated(outcome.bindingId(), task.ownerUserId);
                }
                cancelSiblings(task.ownerUserId, taskId);
            } else if (outcome.status() == Status.EXPIRED) {
                finish(task, Status.EXPIRED, null,
                        outcome.error() == null ? "二维码已过期，请重新生成" : outcome.error());
            }
        } catch (Exception e) {
            // 单次轮询失败不终止任务——网络抖动很常见，下一轮继续。
            log.debug("channel qr poll failed taskId={}", taskId, e);
        }
    }

    private void finish(QrTask task, Status status, Long bindingId, String error) {
        task.status = status;
        task.bindingId = bindingId;
        task.error = error;
        cancel(task);
    }

    /**
     * 清掉同一用户的其他待扫任务。
     * <p>必须保留刚完成的那一个：前端还要 poll 一次拿到 {@code COMPLETED} 才能关弹层。</p>
     */
    private void cancelSiblings(String ownerUserId, String keepTaskId) {
        tasks.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(keepTaskId)) {
                return false;
            }
            QrTask other = entry.getValue();
            if (!ownerUserId.equals(other.ownerUserId)) {
                return false;
            }
            cancel(other);
            return true;
        });
    }

    private void cancel(QrTask task) {
        ScheduledFuture<?> future = task.future;
        if (future != null) {
            future.cancel(false);
            task.future = null;
        }
    }

    private void evictStale() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        tasks.entrySet().removeIf(entry -> {
            QrTask task = entry.getValue();
            if (task.createdAt.isAfter(cutoff)) {
                return false;
            }
            cancel(task);
            return true;
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        tasks.clear();
    }

    private static final class QrTask {
        private final String ownerUserId;
        private final Instant createdAt;
        private volatile Status status = Status.PENDING;
        private volatile Long bindingId;
        private volatile String error;
        private volatile ScheduledFuture<?> future;

        private QrTask(String ownerUserId, Instant createdAt) {
            this.ownerUserId = ownerUserId;
            this.createdAt = createdAt;
        }

        private boolean isFresh() {
            return status == Status.PENDING && !isTimedOut();
        }

        private boolean isTimedOut() {
            return createdAt.plusSeconds(TASK_TTL_SECONDS).isBefore(Instant.now());
        }

        private long remainingSeconds() {
            long remaining = TASK_TTL_SECONDS - Duration.between(createdAt, Instant.now()).toSeconds();
            return Math.max(0, remaining);
        }
    }
}
