package org.wwz.ai.domain.agent.runtime.tasklist;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentTreeLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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
    /** 首版单实例强一致的树控状态；跨实例配额需要后续数据库级改造。 */
    private final Map<String, Set<String>> knownTreeAgents = new HashMap<>();
    private final Map<String, Set<String>> aliveTreeAgents = new HashMap<>();
    private final Map<String, Set<String>> directChildren = new HashMap<>();
    private final Map<String, String> childParents = new HashMap<>();

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
                // hydrate 只恢复持久化快照。running 行可能仍有同进程 Future，也可能属于
                // 另一个实例；是否收敛为 lost 只能由启动对账器统一判断，不能在这里误杀。
                if (task.getCancellation() == null) {
                    task.setCancellation(new RunCancellation());
                }
                // Hub 内已有 live 任务时不要被 DB 孤儿态覆盖
                tasks.putIfAbsent(task.getId(), task);
                recordHydratedTreeIdentity(task);
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

    public record TreeQuotaDecision(boolean allowed, String reason) {

        static TreeQuotaDecision allow() {
            return new TreeQuotaDecision(true, null);
        }

        static TreeQuotaDecision deny(String reason) {
            return new TreeQuotaDecision(false, reason);
        }
    }

    /**
     * 在创建子会话、child ledger run、后台任务之前原子预占树名额。
     * 同一个 agentId 复活只重新占用存活名额，不重复消耗累计/直接子节点名额。
     */
    public synchronized TreeQuotaDecision reserveTreeAgent(String rootSessionId,
                                                           String parentAgentId,
                                                           String agentId) {
        String root = StringUtils.trimToNull(rootSessionId);
        String parent = StringUtils.trimToNull(parentAgentId);
        String agent = StringUtils.trimToNull(agentId);
        if (root == null || parent == null || agent == null) {
            return TreeQuotaDecision.deny("missing tree identity");
        }

        Set<String> known = knownTreeAgents.computeIfAbsent(root, key -> new HashSet<>());
        boolean addedKnown = known.add(agent);
        if (known.size() > SubAgentTreeLimits.MAX_AGENTS_PER_TREE) {
            if (addedKnown) {
                known.remove(agent);
            }
            return TreeQuotaDecision.deny("单树累计子 Agent 已达上限 "
                    + SubAgentTreeLimits.MAX_AGENTS_PER_TREE);
        }

        Set<String> children = directChildren.computeIfAbsent(parent, key -> new HashSet<>());
        String existingParent = childParents.get(agent);
        boolean movedChild = existingParent != null && !existingParent.equals(parent);
        boolean addedChild = existingParent == null;
        if (addedChild) {
            children.add(agent);
            childParents.put(agent, parent);
        } else if (movedChild) {
            Set<String> previousChildren = directChildren.get(existingParent);
            if (previousChildren != null) {
                previousChildren.remove(agent);
            }
            children.add(agent);
            childParents.put(agent, parent);
        }
        Set<String> alive = aliveTreeAgents.computeIfAbsent(root, key -> new HashSet<>());
        boolean addedAlive = alive.add(agent);
        if (alive.size() > SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE) {
            if (addedAlive) {
                alive.remove(agent);
            }
            rollbackDirectChild(agent, parent, existingParent);
            if (addedKnown) {
                known.remove(agent);
            }
            return TreeQuotaDecision.deny("单树同时存活子 Agent 已达上限 "
                    + SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE);
        }
        int activeDirectChildren = 0;
        for (String child : children) {
            if (isActiveTreeChild(root, child)) {
                activeDirectChildren++;
            }
        }
        if (activeDirectChildren > SubAgentTreeLimits.MAX_DIRECT_CHILDREN) {
            if (addedAlive) {
                alive.remove(agent);
            }
            rollbackDirectChild(agent, parent, existingParent);
            if (addedKnown) {
                known.remove(agent);
            }
            return TreeQuotaDecision.deny("单个 Agent 的直接子节点已达上限 "
                    + SubAgentTreeLimits.MAX_DIRECT_CHILDREN);
        }
        return TreeQuotaDecision.allow();
    }

    /** 前台 turn 或后台逻辑任务真正进入终态后释放存活名额；累计名额永不回收。 */
    public synchronized void releaseTreeAgent(String rootSessionId, String agentId) {
        String root = StringUtils.trimToNull(rootSessionId);
        String agent = StringUtils.trimToNull(agentId);
        if (root == null || agent == null) {
            return;
        }
        Set<String> alive = aliveTreeAgents.get(root);
        if (alive != null) {
            alive.remove(agent);
        }
    }

    private boolean isActiveTreeChild(String root, String agent) {
        Set<String> alive = aliveTreeAgents.get(root);
        return alive != null && alive.contains(agent);
    }

    private void rollbackDirectChild(String agent, String parent, String previousParent) {
        Set<String> children = directChildren.get(parent);
        if (children != null) {
            children.remove(agent);
        }
        if (previousParent == null) {
            childParents.remove(agent);
        } else {
            childParents.put(agent, previousParent);
            directChildren.computeIfAbsent(previousParent, key -> new HashSet<>()).add(agent);
        }
    }

    /** 调用方已持有 registry monitor；按任务行身份释放，供所有终态迁移共用。 */
    private void releaseTreeSlotLocked(RuntimeBackgroundTask task) {
        if (task == null) {
            return;
        }
        releaseTreeAgent(task.getRootSessionId(), task.getAgentId());
    }

    private void recordHydratedTreeIdentity(RuntimeBackgroundTask task) {
        if (task == null || StringUtils.isBlank(task.getAgentId())) {
            return;
        }
        String root = StringUtils.trimToNull(task.getRootSessionId());
        if (root == null) {
            return;
        }
        knownTreeAgents.computeIfAbsent(root, key -> new HashSet<>()).add(task.getAgentId());
        if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
            aliveTreeAgents.computeIfAbsent(root, key -> new HashSet<>()).add(task.getAgentId());
        }
        String parent = StringUtils.trimToNull(task.getParentAgentId());
        if (parent != null) {
            directChildren.computeIfAbsent(parent, key -> new HashSet<>()).add(task.getAgentId());
            childParents.putIfAbsent(task.getAgentId(), parent);
        }
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

    /**
     * 认领一次通知投递权。
     * <p>只有终态任务可认领；内存 CAS 保证同一个任务只入队一次。补投判断不看本标记，
     * 只看 {@code notified}（可能跨进程丢失）。</p>
     */
    public boolean claimNotification(String taskId) {
        Optional<RuntimeBackgroundTask> found = get(taskId);
        if (found.isEmpty()) {
            return false;
        }
        RuntimeBackgroundTask task = found.get();
        if (!isNotifiable(task)) {
            return false;
        }
        AtomicBoolean claimed = task.getNotificationClaimed();
        if (claimed == null) {
            synchronized (task) {
                claimed = task.getNotificationClaimed();
                if (claimed == null) {
                    claimed = new AtomicBoolean(false);
                    task.setNotificationClaimed(claimed);
                }
            }
        }
        return claimed.compareAndSet(false, true);
    }

    /**
     * 通知已随工作记忆成功持久化：写 {@code notified=1}。
     * <p>只允许在 run 成功收口后调用；失败/取消不落标记，下一轮补投。</p>
     */
    public void markNotified(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        Set<String> ids = taskIds.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .collect(Collectors.toSet());
        for (String taskId : ids) {
            get(taskId).ifPresent(task -> {
                if (Boolean.TRUE.equals(task.getNotified())) {
                    return;
                }
                task.setNotified(Boolean.TRUE);
                persist(task);
            });
        }
    }

    private static boolean isNotifiable(RuntimeBackgroundTask task) {
        if (task == null) {
            return false;
        }
        String status = task.getStatus();
        return RuntimeBackgroundTask.STATUS_COMPLETED.equals(status)
                || RuntimeBackgroundTask.STATUS_FAILED.equals(status)
                || RuntimeBackgroundTask.STATUS_LOST.equals(status);
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
            releaseTreeSlotLocked(task);
        }
        // 状态变更在窄临界区内完成；DB 与通知放到锁外，避免慢库阻塞同会话其它状态迁移。
        persist(task);
        notifyTask(task);
        return Optional.of(task);
    }

    /** 后台子 Agent 暂不向上回传；status 保持 running，只改变 phase。 */
    public Optional<RuntimeBackgroundTask> markWaiting(String taskId) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null || !RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.ofNullable(task);
            }
            task.setPhase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND);
            task.setEndedAtMs(null);
        }
        persist(task);
        return Optional.of(task);
    }

    public Optional<RuntimeBackgroundTask> markRunning(String taskId) {
        RuntimeBackgroundTask task;
        synchronized (this) {
            task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null || !RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                return Optional.ofNullable(task);
            }
            task.setPhase(RuntimeBackgroundTask.PHASE_RUNNING);
        }
        persist(task);
        return Optional.of(task);
    }

    /**
     * 后台结果/消息触发的一个新收尾轮。只有 WAITING 任务需要计数；任务已由另一轮
     * 标成 running 时直接复用，不重复消耗轮次。
     *
     * @return false 表示任务不存在、已终态或收尾轮已达上限
     */
    public boolean tryBeginClosingTurn(String taskId) {
        RuntimeBackgroundTask changed = null;
        boolean allowed;
        synchronized (this) {
            RuntimeBackgroundTask task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task == null || !RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                allowed = false;
            } else if (!RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND.equals(task.getPhase())) {
                allowed = true;
            } else {
                int count = task.getClosingTurnCount() == null ? 0 : task.getClosingTurnCount();
                allowed = count < SubAgentTreeLimits.MAX_CLOSING_TURNS;
                if (allowed) {
                    task.setClosingTurnCount(count + 1);
                    task.setPhase(RuntimeBackgroundTask.PHASE_RUNNING);
                    changed = task;
                }
            }
        }
        if (changed != null) {
            persist(changed);
        }
        return allowed;
    }

    /** 唤醒启动失败时退还本轮计数，任务随即回到 WAITING。 */
    public void rollbackClosingTurn(String taskId) {
        RuntimeBackgroundTask changed = null;
        synchronized (this) {
            RuntimeBackgroundTask task = tasks.get(StringUtils.trimToEmpty(taskId));
            if (task != null
                    && RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                    && task.getClosingTurnCount() != null
                    && task.getClosingTurnCount() > 0) {
                task.setClosingTurnCount(task.getClosingTurnCount() - 1);
                changed = task;
            }
        }
        if (changed != null) {
            persist(changed);
        }
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
            releaseTreeSlotLocked(task);
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
            releaseTreeSlotLocked(task);
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
            releaseTreeSlotLocked(task);
        }
        // 取消钩子/中断 Future 可能是阻塞动作，放到锁外执行。
        if (task.getCancellation() != null) {
            task.getCancellation().cancel(RunCancellation.REASON_TASK_STOP);
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
                            releaseTreeSlotLocked(task);
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

    /** 只返回指定 Agent 创建的任务；父子共享 registry 时不能把别人的任务算进来。 */
    public List<RuntimeBackgroundTask> listRunningOwnedBy(String ownerAgentId) {
        String owner = StringUtils.trimToNull(ownerAgentId);
        if (owner == null) {
            return List.of();
        }
        List<RuntimeBackgroundTask> result = new ArrayList<>();
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                    && owner.equals(StringUtils.trimToEmpty(task.getOwnerAgentId()))) {
                result.add(task);
            }
        }
        return result;
    }

    /** 已终态但尚未注入子 Agent 上下文的任务，同样属于“未静默”。 */
    public boolean hasUnconsumedOwnedBy(String ownerAgentId) {
        String owner = StringUtils.trimToNull(ownerAgentId);
        if (owner == null) {
            return false;
        }
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                    && !RuntimeBackgroundTask.STATUS_STOPPED.equals(task.getStatus())
                    && !Boolean.TRUE.equals(task.getNotified())
                    && owner.equals(StringUtils.trimToEmpty(task.getOwnerAgentId()))) {
                return true;
            }
        }
        return false;
    }

    /** 直聊/唤醒时按子会话反查仍在等待的逻辑任务。 */
    public Optional<RuntimeBackgroundTask> findWaitingByChildSessionId(String childSessionId) {
        String child = StringUtils.trimToNull(childSessionId);
        if (child == null) {
            return Optional.empty();
        }
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                    && RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND.equals(task.getPhase())
                    && child.equals(StringUtils.trimToEmpty(task.getChildSessionId()))) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /** 只读查询当前 Agent 直接派发过的子 Agent 任务（含终态）。 */
    public List<RuntimeBackgroundTask> listOwnedBy(String ownerAgentId) {
        String owner = StringUtils.trimToNull(ownerAgentId);
        if (owner == null) {
            return List.of();
        }
        List<RuntimeBackgroundTask> result = new ArrayList<>();
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (owner.equals(StringUtils.trimToEmpty(task.getOwnerAgentId()))) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 沿显式父链判断调用者是否能操作目标子树。SendMessage / TaskStop 默认要求“直接子”，
     * 只有 TaskStop(recursive=true) 仍用这个方法递归停止整棵子树。
     */
    public boolean callerOwnsTask(RuntimeBackgroundTask task, String callerAgentId) {
        if (task == null || StringUtils.isBlank(callerAgentId)) {
            return false;
        }
        String caller = callerAgentId.trim();
        if (caller.equals(StringUtils.trimToEmpty(task.getOwnerAgentId()))
                || caller.equals(StringUtils.trimToEmpty(task.getParentAgentId()))) {
            return true;
        }
        // 旧任务行/旧数据库没有显式父链时，owner 为空代表历史兼容：调用方就是原始所有者。
        if (StringUtils.isBlank(task.getOwnerAgentId())
                && StringUtils.isBlank(task.getParentAgentId())) {
            return true;
        }
        Set<String> visited = new java.util.HashSet<>();
        String ancestor = StringUtils.trimToNull(task.getParentAgentId());
        while (ancestor != null && visited.add(ancestor)) {
            if (caller.equals(ancestor)) {
                return true;
            }
            Optional<RuntimeBackgroundTask> ancestorTask = findLatestByAgentId(ancestor);
            if (ancestorTask.isEmpty()) {
                return false;
            }
            ancestor = StringUtils.trimToNull(ancestorTask.get().getParentAgentId());
        }
        return false;
    }

    /**
     * 返回目标的直接派发父 Agent。
     * 用来给出“请先唤醒谁”的可执行提示，不扩大任何操作权限。
     */
    public String findOwningParentAgentId(RuntimeBackgroundTask task) {
        if (task == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(
                StringUtils.trimToNull(task.getParentAgentId()),
                StringUtils.trimToNull(task.getOwnerAgentId()));
    }

    /** 目标 Agent 自身任务及其所有后代运行任务，供 TaskStop 递归取消。 */
    public List<RuntimeBackgroundTask> listRunningSubtree(String agentId) {
        String target = StringUtils.trimToNull(agentId);
        if (target == null) {
            return List.of();
        }
        List<RuntimeBackgroundTask> result = new ArrayList<>();
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())) {
                continue;
            }
            if (target.equals(StringUtils.trimToEmpty(task.getAgentId()))
                    || callerOwnsTask(task, target)) {
                result.add(task);
            }
        }
        return result;
    }

    /** 按子会话找任一活跃逻辑任务；WAITING/Wake 绑定原父目标时使用。 */
    public Optional<RuntimeBackgroundTask> findActiveByChildSessionId(String childSessionId) {
        String child = StringUtils.trimToNull(childSessionId);
        if (child == null) {
            return Optional.empty();
        }
        for (RuntimeBackgroundTask task : tasks.values()) {
            if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                    && child.equals(StringUtils.trimToEmpty(task.getChildSessionId()))) {
                return Optional.of(task);
            }
        }
        if (persistence != null) {
            try {
                Optional<RuntimeBackgroundTask> found =
                        persistence.findActiveBackgroundTaskByChildSessionId(child);
                if (found.isPresent()) {
                    RuntimeBackgroundTask task = found.get();
                    if (task.getCancellation() == null) {
                        task.setCancellation(new RunCancellation());
                    }
                    tasks.putIfAbsent(task.getId(), task);
                    return Optional.ofNullable(tasks.get(task.getId()));
                }
            } catch (Exception ignored) {
                // best-effort; 内存态不可用时就按没有活跃逻辑任务处理。
            }
        }
        return Optional.empty();
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
