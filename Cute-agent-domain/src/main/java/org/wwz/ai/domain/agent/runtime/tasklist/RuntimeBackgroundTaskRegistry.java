package org.wwz.ai.domain.agent.runtime.tasklist;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 后台运行任务注册表。
 * 可选挂载 {@link TasklistPersistencePort} 实现跨 run 持久化。
 */
@Slf4j
public class RuntimeBackgroundTaskRegistry {

    private final Map<String, RuntimeBackgroundTask> tasks = new ConcurrentHashMap<>();
    /**
     * 注册先后序号，供 {@link #findLatestByAgentId} 判定「最新」。
     * <p>
     * 不能只靠 {@code startedAtMs}：同一毫秒内连开两次（SendMessage 唤醒紧接着首跑）
     * 时间戳相同，而 {@link ConcurrentHashMap} 遍历无序，结果会随机。序号是唯一可靠依据。
     * 从持久化 hydrate 的任务没有序号（值为 null），回退按时间戳比较。
     */
    private final Map<String, Long> registrationOrder = new ConcurrentHashMap<>();
    /** 后台 run 的 agentId 占位：提交前 CAS，防止两次 SendMessage 同时续跑同一子 Agent。 */
    private final Map<String, Boolean> agentRunClaims = new ConcurrentHashMap<>();
    private final AtomicLong orderCounter = new AtomicLong();
    private final String sessionId;
    private final TasklistPersistencePort persistence;

    public RuntimeBackgroundTaskRegistry() {
        this(null, null);
    }

    public RuntimeBackgroundTaskRegistry(String sessionId, TasklistPersistencePort persistence) {
        this.sessionId = StringUtils.trimToNull(sessionId);
        this.persistence = persistence;
        hydrate();
    }

    private void hydrate() {
        if (persistence == null || sessionId == null) {
            return;
        }
        try {
            List<RuntimeBackgroundTask> loaded = persistence.loadBackgroundTasks(sessionId);
            if (loaded == null) {
                return;
            }
            for (RuntimeBackgroundTask task : loaded) {
                if (task == null || StringUtils.isBlank(task.getId())) {
                    continue;
                }
            // Hub 首次创建时 hydrate：无 Future 的 running 视为重启孤儿（本进程无法 await）
            if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                task.setStatus(RuntimeBackgroundTask.STATUS_FAILED);
                task.setErrorMsg(StringUtils.defaultIfBlank(
                        task.getErrorMsg(), "interrupted by process restart"));
                if (task.getEndedAtMs() == null) {
                    task.setEndedAtMs(System.currentTimeMillis());
                }
                persist(task);
            }
            if (task.getCancellation() == null) {
                task.setCancellation(new RunCancellation());
            }
            // Hub 内已有 live 任务时不要被 DB 孤儿态覆盖
            tasks.putIfAbsent(task.getId(), task);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public RuntimeBackgroundTask register(String type, String description, String command) {
        String id = newTaskId();
        RuntimeBackgroundTask task = RuntimeBackgroundTask.builder()
                .id(id)
                .type(StringUtils.defaultIfBlank(type, RuntimeBackgroundTask.TYPE_GENERIC))
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .description(description)
                .command(command)
                .startedAtMs(System.currentTimeMillis())
                .cancellation(new RunCancellation())
                .build();
        tasks.put(id, task);
        registrationOrder.put(id, orderCounter.incrementAndGet());
        persist(task);
        return task;
    }

    public RuntimeBackgroundTask registerLocalAgent(String description,
                                                    String agentType,
                                                    String prompt) {
        RuntimeBackgroundTask task = register(
                RuntimeBackgroundTask.TYPE_LOCAL_AGENT,
                description,
                "subagent:" + StringUtils.defaultIfBlank(agentType, "general-purpose"));
        task.setAgentType(agentType);
        task.setPrompt(prompt);
        persist(task);
        return task;
    }

    /**
     * 原子占位一个 agentId 的后台执行权。
     *
     * @return false 表示该 agentId 已有未收尾的后台 run
     */
    public boolean tryClaimAgentRun(String agentId) {
        String key = StringUtils.trimToNull(agentId);
        return key != null && agentRunClaims.putIfAbsent(key, Boolean.TRUE) == null;
    }

    public void releaseAgentRun(String agentId) {
        String key = StringUtils.trimToNull(agentId);
        if (key != null) {
            agentRunClaims.remove(key);
        }
    }

    public void bindFuture(String taskId, Future<?> future) {
        get(taskId).ifPresent(task -> task.setFuture(future));
    }

    public void bindAgentId(String taskId, String agentId) {
        get(taskId).ifPresent(task -> {
            if (StringUtils.isNotBlank(agentId)) {
                task.setAgentId(agentId.trim());
                persist(task);
            }
        });
    }

    public Optional<RuntimeBackgroundTask> get(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return Optional.empty();
        }
        String id = taskId.trim();
        RuntimeBackgroundTask local = tasks.get(id);
        if (local != null) {
            return Optional.of(local);
        }
        // 跨 run：内存未命中时回源 DB，并并入本进程 registry
        return loadFromPersistence(id);
    }

    /**
     * 按 agentId 找该子 Agent 最近一次运行。
     * <p>
     * 模型侧只认 agentId（task_id 不对模型暴露）：同一个子 Agent 被 SendMessage 唤醒会新建
     * task，所以「取最新」等价于「取当前/最近一次运行」，正是调用方要的语义。
     */
    public Optional<RuntimeBackgroundTask> findLatestByAgentId(String agentId) {
        String target = StringUtils.trimToNull(agentId);
        if (target == null) {
            return Optional.empty();
        }
        RuntimeBackgroundTask latest = null;
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (task == null || !target.equals(StringUtils.trimToEmpty(task.getAgentId()))) {
                continue;
            }
            if (latest == null || isNewer(task, latest)) {
                latest = task;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** 先比注册序号（同毫秒也能分出先后），序号缺失时回退时间戳。 */
    private boolean isNewer(RuntimeBackgroundTask candidate, RuntimeBackgroundTask current) {
        Long candidateOrder = registrationOrder.get(candidate.getId());
        Long currentOrder = registrationOrder.get(current.getId());
        if (candidateOrder != null && currentOrder != null) {
            return candidateOrder > currentOrder;
        }
        if (candidateOrder != null) {
            return true;
        }
        if (currentOrder != null) {
            return false;
        }
        return candidate.getStartedAtMs() >= current.getStartedAtMs();
    }

    private Optional<RuntimeBackgroundTask> loadFromPersistence(String taskId) {
        if (persistence == null || sessionId == null) {
            return Optional.empty();
        }
        try {
            Optional<RuntimeBackgroundTask> found = persistence.findBackgroundTask(sessionId, taskId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            RuntimeBackgroundTask task = found.get();
            if (task.getCancellation() == null) {
                task.setCancellation(new RunCancellation());
            }
            tasks.putIfAbsent(taskId, task);
            return Optional.ofNullable(tasks.get(taskId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Optional<RuntimeBackgroundTask> complete(String taskId, SubAgentResult result) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null) {
                return Optional.empty();
            }
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.of(task);
            }
            task.setStatus(RuntimeBackgroundTask.STATUS_COMPLETED);
            task.setEndedAtMs(System.currentTimeMillis());
            if (result != null) {
                task.setAgentId(result.getAgentId());
                task.setAgentType(result.getAgentType());
                task.setOutput(result.getContent());
                task.setTotalToolUseCount(result.getTotalToolUseCount());
                task.setTotalDurationMs(result.getTotalDurationMs());
                if (StringUtils.isNotBlank(result.getErrorMsg())) {
                    task.setErrorMsg(result.getErrorMsg());
                }
            }
        }
        // 状态变更在窄临界区内完成；DB 与通知放到锁外，避免慢库阻塞同会话其它状态迁移。
        persist(task);
        notifyTask(task);
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> fail(String taskId, SubAgentResult result) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null) {
                return Optional.empty();
            }
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.of(task);
            }
            task.setStatus(RuntimeBackgroundTask.STATUS_FAILED);
            task.setEndedAtMs(System.currentTimeMillis());
            if (result != null) {
                task.setAgentId(result.getAgentId());
                task.setAgentType(result.getAgentType());
                task.setOutput(result.getContent());
                task.setErrorMsg(StringUtils.defaultIfBlank(result.getErrorMsg(), "failed"));
                task.setTotalToolUseCount(result.getTotalToolUseCount());
                task.setTotalDurationMs(result.getTotalDurationMs());
            } else {
                task.setErrorMsg("failed");
            }
        }
        persist(task);
        notifyTask(task);
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> fail(String taskId, String errorMsg) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null) {
                return Optional.empty();
            }
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.of(task);
            }
            task.setStatus(RuntimeBackgroundTask.STATUS_FAILED);
            task.setEndedAtMs(System.currentTimeMillis());
            task.setErrorMsg(StringUtils.defaultIfBlank(errorMsg, "failed"));
        }
        persist(task);
        notifyTask(task);
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> stop(String taskId) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null) {
                return Optional.empty();
            }
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.of(task);
            }
            task.setStatus(RuntimeBackgroundTask.STATUS_STOPPED);
            task.setEndedAtMs(System.currentTimeMillis());
        }
        // 取消钩子/中断 Future 可能是阻塞动作，放到锁外执行。
        if (task.getCancellation() != null) {
            task.getCancellation().cancel("task_stop");
        }
        Future<?> future = task.getFuture();
        if (future != null) {
            future.cancel(true);
        }
        persist(task);
        notifyTask(task);
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> awaitUntilTerminal(String taskId) {
        return awaitUntilTerminal(taskId, null);
    }

    public Optional<RuntimeBackgroundTask> awaitUntilTerminal(String taskId, BooleanSupplier abort) {
        Optional<RuntimeBackgroundTask> found = get(taskId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RuntimeBackgroundTask task = found.get();
        if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
            return Optional.of(task);
        }
        // 无本地 Future 时每轮都要回源 DB；退避到 5s，避免每个等待任务 4 次/秒的持续查询。
        // 同进程任务有 notifyAll，仍会被立即唤醒，不受退避影响。
        long waitMs = 250L;
        while (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
            if (isAborted(abort)) {
                break;
            }
            // 本进程无 Future 时轮询 DB（跨实例或仅落库场景）
            if (task.getFuture() == null && persistence != null && sessionId != null) {
                try {
                    Optional<RuntimeBackgroundTask> remote = persistence.findBackgroundTask(sessionId, taskId);
                    if (remote.isPresent()
                            && !RuntimeBackgroundTask.STATUS_RUNNING.equals(remote.get().getStatus())) {
                        RuntimeBackgroundTask done = remote.get();
                        // DB 读取在锁外完成，期间本地可能已经落终态：合并必须回到与
                        // complete/fail/stop 相同的 registry 锁并重新判定，
                        // 否则陈旧远端行会把 completed 降级成 failed 并清空本地 output/agentId。
                        synchronized (this) {
                            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                                break;
                            }
                            if (done.getStatus() != null) {
                                task.setStatus(done.getStatus());
                            }
                            applyIfNotNull(done.getOutput(), task::setOutput);
                            applyIfNotNull(done.getErrorMsg(), task::setErrorMsg);
                            applyIfNotNull(done.getAgentId(), task::setAgentId);
                            applyIfNotNull(done.getAgentType(), task::setAgentType);
                            applyIfNotNull(done.getTotalToolUseCount(), task::setTotalToolUseCount);
                            applyIfNotNull(done.getTotalDurationMs(), task::setTotalDurationMs);
                            applyIfNotNull(done.getEndedAtMs(), task::setEndedAtMs);
                        }
                        notifyTask(task);
                        break;
                    }
                } catch (Exception ignored) {
                    // fall through to wait
                }
            }
            synchronized (task) {
                if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus()) || isAborted(abort)) {
                    break;
                }
                try {
                    task.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            waitMs = Math.min(waitMs * 2, 5_000L);
        }
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> awaitTerminal(String taskId, long timeoutMs) {
        Optional<RuntimeBackgroundTask> found = get(taskId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (timeoutMs <= 0 || !RuntimeBackgroundTask.STATUS_RUNNING.equals(found.get().getStatus())) {
            return found;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        return awaitUntilTerminal(taskId, () -> System.currentTimeMillis() >= deadline);
    }

    private static boolean isAborted(BooleanSupplier abort) {
        return abort != null && abort.getAsBoolean();
    }

    private static <T> void applyIfNotNull(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    public List<RuntimeBackgroundTask> listRunning() {
        List<RuntimeBackgroundTask> result = new ArrayList<>();
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                result.add(task);
            }
        }
        return result;
    }

    public List<RuntimeBackgroundTask> listAll() {
        return new ArrayList<>(tasks.values());
    }

    private void persist(RuntimeBackgroundTask task) {
        if (persistence == null || sessionId == null || task == null) {
            return;
        }
        try {
            persistence.upsertBackgroundTask(sessionId, task);
        } catch (Exception e) {
            // 落库失败会导致跨轮 TaskOutput not_found，保留完整异常便于运维排查。
            log.error("后台任务落库失败 sessionId={} taskId={}", sessionId, task.getId(), e);
        }
    }

    private static String newTaskId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void notifyTask(RuntimeBackgroundTask task) {
        if (task == null) {
            return;
        }
        synchronized (task) {
            task.notifyAll();
        }
    }
}
