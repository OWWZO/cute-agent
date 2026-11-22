package org.wwz.ai.domain.agent.runtime.subagent;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.stream.ToolResultStreamPayload;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationSerializer;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 后台子 Agent 提交与结算。创建（Agent）与续跑（SendMessage）共用。
 */
@Slf4j
public final class BackgroundSubAgentExecutor {

    /**
     * 后台子 Agent 以 LLM、网络和文件等待为主，使用虚拟线程避免阻塞平台线程。
     * 业务并发仍由 SubAgentRunner 的许可和后续树级配额控制。
     */
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("bg-subagent-", 1).factory());

    private BackgroundSubAgentExecutor() {
    }

    public static ToolResultPayload submit(AgentContext parent,
                                           SubAgentRunner subAgentRunner,
                                           ActiveAgentRunRegistry runRegistry,
                                           String description,
                                           String prompt,
                                           String subagentType,
                                           String resumeAgentId,
                                           String parentToolUseId,
                                           String parentToolName) {
        return submit(parent, subAgentRunner, runRegistry, description, prompt, subagentType,
                resumeAgentId, null, parentToolUseId, parentToolName);
    }

    public static ToolResultPayload submit(AgentContext parent,
                                           SubAgentRunner subAgentRunner,
                                           ActiveAgentRunRegistry runRegistry,
                                           String description,
                                           String prompt,
                                           String subagentType,
                                           String resumeAgentId,
                                           String preferredAgentIdOverride,
                                           String parentToolUseId,
                                           String parentToolName) {
        RuntimeBackgroundTaskRegistry registry = parent.requireBackgroundTasks();
        final String toolName = StringUtils.defaultIfBlank(parentToolName, AgentDispatchTool.NAME);
        boolean resume = StringUtils.isNotBlank(resumeAgentId);
        final String preferredAgentId = resume
                ? resumeAgentId.trim()
                : StringUtils.defaultIfBlank(preferredAgentIdOverride, SubAgentContextFactory.newAgentId());
        // 先原子占位再登记任务：isActive / isBackgroundRunning 的检查与 submit 之间没有原子性，
        // 两次并发的 SendMessage 会各自登记一条 run，并共享同一 childSessionId/working memory。
        if (!registry.tryClaimAgentRun(preferredAgentId)) {
            return ToolResultPayload.softFailData(toolName, softBusy(toolName, preferredAgentId));
        }
        int childDepth = SubAgentContextFactory.childDelegationDepth(parent);
        if (childDepth > SubAgentTreeLimits.MAX_DELEGATION_DEPTH) {
            registry.releaseAgentRun(preferredAgentId);
            return ToolResultPayload.failureFrom(
                    "Agent 执行失败：子 Agent 深度上限为 "
                            + SubAgentTreeLimits.MAX_DELEGATION_DEPTH,
                    quotaDeniedData(toolName, preferredAgentId, "max_depth"));
        }
        RuntimeBackgroundTaskRegistry.TreeQuotaDecision quota = registry.reserveTreeAgent(
                SubAgentContextFactory.rootSessionId(parent),
                SubAgentContextFactory.currentAgentId(parent),
                preferredAgentId);
        if (!quota.allowed()) {
            registry.releaseAgentRun(preferredAgentId);
            return ToolResultPayload.failureFrom(
                    "Agent 执行失败：" + quota.reason(),
                    quotaDeniedData(toolName, preferredAgentId, quota.reason()));
        }

        RuntimeBackgroundTask task;
        try {
            task = registry.registerLocalAgent(description, subagentType, prompt);
        } catch (RuntimeException e) {
            registry.releaseAgentRun(preferredAgentId);
            registry.releaseTreeAgent(
                    SubAgentContextFactory.rootSessionId(parent), preferredAgentId);
            throw e;
        }
        RunCancellation taskCancel = task.getCancellation() != null
                ? task.getCancellation()
                : new RunCancellation();
        task.setCancellation(taskCancel);

        task.setAgentId(preferredAgentId);
        // childSessionId 由 agentId 确定性派生，与 SubAgentRunner 落库使用的完全一致；
        // 先写入 task，运行中/终态投影即可带上它，无需等待子会话行真正落库。
        final String childSessionId = SubAgentContextFactory.newChildSessionId(preferredAgentId);
        task.setChildSessionId(childSessionId);
        String parentAgentId = parent.isSubAgent()
                ? parent.getSubAgentId()
                : StringUtils.defaultIfBlank(parent.getSessionId(), parent.getRequestId());
        String rootSessionId = StringUtils.defaultIfBlank(parent.getRootSessionId(), parent.getSessionId());
        task.setParentSessionId(parent.getSessionId());
        task.setParentRequestId(parent.getRequestId());
        task.setParentToolUseId(parentToolUseId);
        task.setParentAgentId(parentAgentId);
        task.setOwnerAgentId(parentAgentId);
        task.setRootSessionId(rootSessionId);
        task.setDelegationDepth((parent.getDelegationDepth() == null ? 0 : parent.getDelegationDepth()) + 1);
        task.setPhase(RuntimeBackgroundTask.PHASE_RUNNING);
        // 该入口始终提交到后台虚拟线程执行器；上游 task 池的 CallerRuns
        // 只代表 Agent 工具调用回到当前线程，不代表后台任务本身被内联执行。
        task.setExecutionMode(RuntimeBackgroundTask.EXECUTION_MODE_POOL);
        task.setClosingTurnCount(0);
        registry.bindAgentId(task.getId(), preferredAgentId);

        final String desc = description;
        final String pmt = prompt;
        final String type = subagentType;
        final String resumeId = resume ? resumeAgentId.trim() : null;
        final String taskId = task.getId();
        final String sessionKey = StringUtils.defaultIfBlank(parent.getSessionId(), parent.getRequestId());
        SessionAgentMailboxHub.queue(sessionKey, preferredAgentId);

        Future<?> future;
        try {
            future = BACKGROUND_EXECUTOR.submit(() -> {
            try {
                SubAgentResult result = subAgentRunner.run(
                        parent, desc, pmt, type, resumeId, taskCancel, preferredAgentId,
                        parentToolUseId, taskId);
                registry.bindAgentId(taskId, result.getAgentId());
                if (taskCancel.isCancelled()
                        || RuntimeBackgroundTask.STATUS_STOPPED.equals(
                        registry.get(taskId).map(RuntimeBackgroundTask::getStatus).orElse(null))) {
                    // 父 run 取消时任务状态可能仍是 running；统一收敛为 stopped，避免重启后被当失败补投。
                    registry.stop(taskId);
                    registry.get(taskId).ifPresent(t -> {
                        if (StringUtils.isBlank(t.getOutput()) && StringUtils.isNotBlank(result.getContent())) {
                            t.setOutput(result.getContent());
                        }
                        if (StringUtils.isBlank(t.getAgentId())) {
                            t.setAgentId(result.getAgentId());
                        }
                        t.setAgentType(result.getAgentType());
                        t.setTotalToolUseCount(result.getTotalToolUseCount());
                        t.setTotalDurationMs(result.getTotalDurationMs());
                    });
                    persistLedgerSettlement(parent, parentToolUseId, toolName,
                            registry.get(taskId).orElse(task));
                    // 用户主动停止不产生完成通知，也不唤醒。
                    emitBackgroundSettlement(parent, parentToolUseId, toolName, desc, pmt, type, result, false,
                            runRegistry, childSessionId, true);
                    return;
                }
                if (result.isCompleted()) {
                    registry.complete(taskId, result);
                    RuntimeBackgroundTask completedTask = registry.get(taskId).orElse(task);
                    persistLedgerSettlement(parent, parentToolUseId, toolName, completedTask);
                    boolean wakeHandled = notifyCompletion(parent, completedTask, parentToolUseId);
                    emitBackgroundSettlement(parent, parentToolUseId, toolName, desc, pmt, type, result, true,
                            runRegistry, childSessionId, !wakeHandled);
                } else if (result.isWaiting()) {
                    // 候选回复只留在子会话；线程释放，父 Agent 继续等同一个逻辑任务。
                    registry.markWaiting(taskId);
                    BackgroundTaskWakePort wakePort = backgroundWakePort(parent);
                    if (wakePort != null) {
                        try {
                            wakePort.onInboxChanged(childSessionId);
                        } catch (Exception wakeError) {
                            log.warn("{} waiting subagent wake check failed taskId={}",
                                    parent.getRequestId(), taskId, wakeError);
                        }
                    }
                } else {
                    registry.fail(taskId, result);
                    RuntimeBackgroundTask failedTask = registry.get(taskId).orElse(task);
                    persistLedgerSettlement(parent, parentToolUseId, toolName, failedTask);
                    boolean wakeHandled = notifyCompletion(parent, failedTask, parentToolUseId);
                    emitBackgroundSettlement(parent, parentToolUseId, toolName, desc, pmt, type, result, false,
                            runRegistry, childSessionId, !wakeHandled);
                }
            } catch (Exception e) {
                log.error("{} background subagent failed taskId={}", parent.getRequestId(), taskId, e);
                if (taskCancel.isCancelled()) {
                    // TaskStop 取消：先登记终态、再结算账本，最后广播（settleAllowed=true 表示不再等唤醒注入）。
                    registry.stop(taskId);
                    persistLedgerSettlement(parent, parentToolUseId, toolName,
                            registry.get(taskId).orElse(task));
                    emitBackgroundSettlement(parent, parentToolUseId, toolName, desc, pmt, type, null, false,
                            runRegistry, childSessionId, true);                    return;
                }
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                registry.fail(taskId, err);
                SubAgentResult failed = SubAgentResult.builder()
                        .status(SubAgentResult.STATUS_FAILED)
                        .agentId(preferredAgentId)
                        .agentType(type)
                        .description(desc)
                        .prompt(pmt)
                        .errorMsg(err)
                        .build();
                RuntimeBackgroundTask failedTask = registry.get(taskId).orElse(task);
                persistLedgerSettlement(parent, parentToolUseId, toolName, failedTask);
                boolean wakeHandled = notifyCompletion(parent, failedTask, parentToolUseId);
                emitBackgroundSettlement(parent, parentToolUseId, toolName, desc, pmt, type, failed, false,
                        runRegistry, childSessionId, !wakeHandled);
            } finally {
                // 无论正常结束、取消还是异常，都必须释放 agentId 占位，否则该子 Agent 永远无法再次唤醒。
                RuntimeBackgroundTask current = registry.get(taskId).orElse(task);
                if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(current.getStatus())) {
                    registry.releaseTreeAgent(
                            StringUtils.defaultIfBlank(
                                    current.getRootSessionId(), SubAgentContextFactory.rootSessionId(parent)),
                            preferredAgentId);
                }
                registry.releaseAgentRun(preferredAgentId);
            }
            });
        } catch (RejectedExecutionException e) {
            // 执行器关闭等拒绝场景：必须结算已登记的 RUNNING 任务，否则 TaskOutput 永远等不到终态。
            log.warn("{} background subagent rejected, taskId={}, agentId={}",
                    parent.getRequestId(), taskId, preferredAgentId);
            registry.fail(taskId, "background executor rejected");
            registry.releaseTreeAgent(
                    StringUtils.defaultIfBlank(task.getRootSessionId(), SubAgentContextFactory.rootSessionId(parent)),
                    preferredAgentId);
            registry.releaseAgentRun(preferredAgentId);
            SessionAgentMailboxHub.evict(sessionKey, preferredAgentId);
            return ToolResultPayload.fromData(rejectedData(toolName, preferredAgentId, childSessionId,
                    description, subagentType));
        }
        registry.bindFuture(taskId, future);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("ok", true);
        data.put("status", RuntimeBackgroundTask.STATUS_RUNNING);
        // 模型侧只有 agentId 一种句柄：task_id 不暴露，否则模型会拿它去调 TaskOutput。
        data.put("agentId", preferredAgentId);
        data.put("childSessionId", childSessionId);
        data.put("task_type", RuntimeBackgroundTask.TYPE_LOCAL_AGENT);
        data.put("description", description);
        data.put("agentType", StringUtils.defaultIfBlank(subagentType, SubAgentRegistry.TYPE_GENERAL_PURPOSE));
        data.put("run_in_background", true);
        data.put("executionMode", task.getExecutionMode());
        if (resume) {
            data.put("resumed", true);
            data.put("message", "已在后台唤醒子 Agent。任务完成结果会自动回传主 Agent；SendMessage(to=\""
                    + preferredAgentId + "\") 中途指导；TaskStop(agent_id) 取消。");
        } else {
            data.put("message", "后台子 Agent 已启动。任务完成结果会自动回传主 Agent；SendMessage(to=\""
                    + preferredAgentId + "\") 中途指导；TaskStop(agent_id) 取消。");
        }
        return ToolResultPayload.fromData(data);
    }

    /**
     * 判断字符串是否为后台派发 ACK 回执：{@code "run_in_background":true} 说明子 Agent
     * 仍在后台运行，父侧工具调用尚未真正完成。回执格式的知识归本类所有（submit 的产物），
     * 账本管线据此不写终态（保持 running + finished_at=null），
     * 让 {@link #settleLedgerIfTerminal} / {@link #persistLedgerSettlement} 的终态更新
     * 能过 mapper 守卫（status=0 AND finished_at IS NULL）。
     */
    public static boolean isBackgroundAckObservation(String llmObservation) {
        if (StringUtils.isBlank(llmObservation)) {
            return false;
        }
        try {
            Map<?, ?> parsed = JSON.parseObject(llmObservation, Map.class);
            return parsed != null && Boolean.TRUE.equals(parsed.get("run_in_background"));
        } catch (Exception e) {
            return false;
        }
    }

    public static void settleLedgerIfTerminal(AgentContext parent,
                                              String parentToolUseId,
                                              String runningObservation) {
        settleLedgerIfTerminal(parent, parentToolUseId, AgentDispatchTool.NAME, runningObservation);
    }

    /**
     * 跨线程/跨 run 完成 WAITING 逻辑任务：父目标、toolCallId 和 registry 都从任务行恢复，
     * 不依赖最初派发线程的闭包。
     */
    public static Optional<RuntimeBackgroundTask> completePersistedTask(
            RuntimeBackgroundTask task,
            SubAgentResult result,
            CuteRuntimeDependencies dependencies,
            AgentExecutionRecorder recorder,
            IExecutionLedgerReadRepository readRepository) {
        return finishPersistedTask(task, result, dependencies, recorder, readRepository, true);
    }

    /** 后台收尾轮耗尽等无法继续执行的情况，按失败终态回传父 Agent。 */
    public static Optional<RuntimeBackgroundTask> failPersistedTask(
            RuntimeBackgroundTask task,
            SubAgentResult result,
            CuteRuntimeDependencies dependencies,
            AgentExecutionRecorder recorder,
            IExecutionLedgerReadRepository readRepository) {
        return finishPersistedTask(task, result, dependencies, recorder, readRepository, false);
    }

    private static Optional<RuntimeBackgroundTask> finishPersistedTask(
            RuntimeBackgroundTask task,
            SubAgentResult result,
            CuteRuntimeDependencies dependencies,
            AgentExecutionRecorder recorder,
            IExecutionLedgerReadRepository readRepository,
            boolean completed) {
        if (task == null || result == null || dependencies == null
                || StringUtils.isBlank(task.getParentSessionId())) {
            return Optional.empty();
        }
        // 子 Agent 与父 Agent 共享根会话的 registry；直接拿 parentSessionId 会在
        // 孙 Agent 场景下落到另一个 registry，导致任务查不到或重复实例。
        RuntimeBackgroundTaskRegistry registry = SessionBackgroundTaskHub.getOrCreate(
                StringUtils.defaultIfBlank(task.getRootSessionId(), task.getParentSessionId()),
                dependencies.getOptionalTasklistPersistencePort());
        RuntimeBackgroundTask current = registry.get(task.getId()).orElse(task);
        if (!RuntimeBackgroundTask.STATUS_RUNNING.equals(current.getStatus())) {
            return Optional.of(current);
        }
        if (completed) {
            registry.complete(task.getId(), result);
        } else {
            registry.fail(task.getId(), result);
        }
        RuntimeBackgroundTask settled = registry.get(task.getId()).orElse(current);
        AgentContext parent = AgentContext.builder()
                .sessionId(settled.getParentSessionId())
                .requestId(settled.getParentRequestId())
                .rootSessionId(StringUtils.defaultIfBlank(
                        settled.getRootSessionId(), settled.getParentSessionId()))
                .runtimeDependencies(dependencies)
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();
        notifyCompletion(parent, settled, settled.getParentToolUseId());
        settlePersistedParentLedger(settled, recorder, readRepository);
        registry.releaseTreeAgent(settled.getRootSessionId(), settled.getAgentId());
        return Optional.of(settled);
    }

    public static void settleLedgerIfTerminal(AgentContext parent,
                                              String parentToolUseId,
                                              String parentToolName,
                                              String runningObservation) {
        if (parent == null || StringUtils.isBlank(parentToolUseId)
                || StringUtils.isBlank(runningObservation)) {
            return;
        }
        try {
            Map<String, Object> receipt = JSON.parseObject(runningObservation, Map.class);
            String agentId = receipt == null ? null : trimToString(receipt.get("agentId"));
            if (StringUtils.isBlank(agentId)) {
                return;
            }
            String toolName = StringUtils.defaultIfBlank(parentToolName, AgentDispatchTool.NAME);
            parent.requireBackgroundTasks().findLatestByAgentId(agentId)
                    .filter(task -> !RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus()))
                    .ifPresent(task -> persistLedgerSettlement(parent, parentToolUseId, toolName, task));
        } catch (Exception e) {
            log.debug("background ledger settlement check skipped: {}", e.getMessage());
        }
    }

    public static boolean shouldSettleParentStream(AgentContext parent) {
        if (!shouldReleaseParentRun(parent)) {
            return false;
        }
        SessionInboxPort inbox = parent.sessionInboxPort();
        if (inbox == null) {
            return true;
        }
        try {
            return !inbox.hasOutstanding(parent.getSessionId());
        } catch (Exception e) {
            // Inbox 查询失败时退回旧语义（仅内存判定），避免连带阻断 run 收口。
            log.warn("session inbox outstanding check failed sessionId={}", parent.getSessionId(), e);
            return true;
        }
    }

    private static boolean shouldReleaseParentRun(AgentContext parent) {
        // 传输层默认挂起（deferBackgroundSettle=true，对齐 ZCode 推送 UX）：父回合关闭且
        // 后台空闲时才发 stream_settle 关流——这之前投影一直活着，结算 tool_result
        // 才能经活连接推送。flag=false 时全解耦：root result 即关流，无需 settle。
        if (parent == null || !parent.isTurnClosed() || !StreamSettlePolicy.deferBackgroundSettle()) {
            return false;
        }
        // 没有唤醒端口时通知无法自动续跑，必须关流避免 SSE 永久挂住。
        // 有唤醒端口且还有通知排队：500ms 后要发 run_wake，连接不能先关。
        String sessionKey = SessionBackgroundTaskHub.keyFor(parent.getSessionId(), parent.getRequestId());
        if (backgroundWakePort(parent) != null && SessionNotificationHub.hasPending(sessionKey)) {
            return false;
        }
        if (SessionBackgroundTaskHub.hasRunning(sessionKey)) {
            return false;
        }
        return true;
    }

    /**
     * 后台任务到达终态：入队模型可见通知，并询问应用层是否接管续跑。
     *
     * @return true 表示续跑已被接管（调用方不要发 stream_settle）
     */
    private static boolean notifyCompletion(AgentContext parent,
                                            RuntimeBackgroundTask task,
                                            String parentToolUseId) {
        if (parent == null || task == null) {
            return false;
        }
        CuteRuntimeDependencies deps = parent.getRuntimeDependencies();
        if (deps != null && deps.getCuteConfig() != null
                && !deps.getCuteConfig().isBackgroundAutoContinuation()) {
            return false;
        }
        // stopped / running 不产生通知；completed / failed / lost 才需要回传。
        if (!RuntimeBackgroundTask.STATUS_COMPLETED.equals(task.getStatus())
                && !RuntimeBackgroundTask.STATUS_FAILED.equals(task.getStatus())
                && !RuntimeBackgroundTask.STATUS_LOST.equals(task.getStatus())) {
            return false;
        }
        if (!parent.requireBackgroundTasks().claimNotification(task.getId())) {
            return false;
        }
        String sessionKey = SessionBackgroundTaskHub.keyFor(parent.getSessionId(), parent.getRequestId());
        TaskNotification notification = TaskNotification.from(task, parentToolUseId);
        SessionInboxPort inbox = deps == null ? null : deps.getOptionalSessionInboxPort();
        if (inbox != null) {
            try {
                inbox.offer(SessionInboxItem.builder()
                        .sessionId(parent.getSessionId())
                        .kind(SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                        .visibility(SessionInboxItem.VISIBILITY_MODEL_ONLY)
                        .state(SessionInboxItem.STATE_PENDING)
                        .idempotencyKey("background:" + task.getId())
                        .payloadJson(SessionInboxPayloads.backgroundNotification(notification))
                        .sourceRunId(parent.getRequestId())
                        .build());
            } catch (Exception e) {
                // 通知仍会进入进程内队列；后台任务表的 notified=0 负责下一次补投。
                log.warn("persist session inbox background notification failed taskId={}", task.getId(), e);
            }
        }
        // 进程内队列保留为即时唤醒提示；数据库 Inbox 才负责跨 run/重启补投。
        SessionNotificationHub.offer(sessionKey, notification);
        BackgroundTaskWakePort wakePort = deps == null ? null : deps.getOptionalBackgroundTaskWakePort();
        if (wakePort == null) {
            return false;
        }
        try {
            return wakePort.onBackgroundTaskCompleted(new BackgroundTaskCompletionEvent(
                    parent.getSessionId(),
                    parent.getRequestId(),
                    task.getId(),
                    task.getAgentId(),
                    task.getChildSessionId(),
                    task.getStatus(),
                    task.getDescription()));
        } catch (Exception e) {
            log.warn("background wake decision failed taskId={}", task.getId(), e);
            return false;
        }
    }

    private static void persistLedgerSettlement(AgentContext parent,
                                                String parentToolUseId,
                                                String parentToolName,
                                                RuntimeBackgroundTask task) {
        if (parent == null || task == null || StringUtils.isBlank(parentToolUseId)
                || !parent.hasActiveLedgerRun()
                || parent.getAgentRunState() == null
                || parent.getExecutionRecorder() == null) {
            return;
        }
        Long toolInvocationId = parent.getAgentRunState().resolveToolInvocationId(parentToolUseId);
        if (toolInvocationId == null) {
            return;
        }

        boolean completed = RuntimeBackgroundTask.STATUS_COMPLETED.equals(task.getStatus());
        String toolName = StringUtils.defaultIfBlank(parentToolName, AgentDispatchTool.NAME);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("ok", completed);
        data.put("status", task.getStatus());
        data.put("agentId", task.getAgentId());
        data.put("agentType", task.getAgentType());
        if (StringUtils.isNotBlank(task.getChildSessionId())) {
            data.put("childSessionId", task.getChildSessionId());
        }
        data.put("description", task.getDescription());
        data.put("content", task.getOutput());
        data.put("totalToolUseCount", task.getTotalToolUseCount());
        data.put("totalDurationMs", task.getTotalDurationMs());
        data.put("run_in_background", true);
        if (StringUtils.isNotBlank(task.getErrorMsg())) {
            data.put("errorMsg", task.getErrorMsg());
        }

        int ledgerStatus = RuntimeBackgroundTask.STATUS_STOPPED.equals(task.getStatus())
                ? ExecutionLedgerConstants.STATUS_STOPPED
                : completed ? ExecutionLedgerConstants.STATUS_SUCCESS : ExecutionLedgerConstants.STATUS_FAILED;
        ToolInvocationFinishRecord record = ToolInvocationFinishRecord.builder()
                .toolInvocationId(toolInvocationId)
                .runId(parent.getAgentRunState().getRunId())
                .requestId(ExecutionLedgerRunSupport.ledgerRequestId(parent))
                .sessionId(parent.getSessionId())
                .toolCallId(parentToolUseId)
                .toolName(toolName)
                .status(ledgerStatus)
                .llmObservation(ToolObservationSerializer.serializeSuccess(data))
                .toolResult(ToolObservationSerializer.serializeSuccess(data))
                .errorMsg(task.getErrorMsg())
                .finishedAt(resolveFinishedAt(task))
                .build();
        try {
            parent.getExecutionRecorder().finishToolInvocation(record);
        } catch (RuntimeException e) {
            log.warn("background sub-agent ledger settle failed, parentSessionId={}, toolCallId={}",
                    parent.getSessionId(), parentToolUseId, e);
            try {
                parent.getExecutionRecorder().finishToolInvocationWithoutTimeline(record);
            } catch (RuntimeException fallbackError) {
                log.warn("background sub-agent ledger fallback failed, parentSessionId={}, toolCallId={}",
                        parent.getSessionId(), parentToolUseId, fallbackError);
            }
        }
    }

    private static void settlePersistedParentLedger(RuntimeBackgroundTask task,
                                                    AgentExecutionRecorder recorder,
                                                    IExecutionLedgerReadRepository readRepository) {
        if (task == null || recorder == null || readRepository == null
                || StringUtils.isBlank(task.getParentRequestId())
                || StringUtils.isBlank(task.getParentToolUseId())) {
            return;
        }
        try {
            var run = readRepository.queryRunByRequestId(task.getParentRequestId());
            if (run == null || run.getId() == null) {
                return;
            }
            List<ToolInvocation> invocations = readRepository.queryToolInvocationsByRunId(run.getId());
            ToolInvocation invocation = invocations == null ? null : invocations.stream()
                    .filter(item -> item != null
                            && task.getParentToolUseId().equals(item.getToolCallId()))
                    .findFirst()
                    .orElse(null);
            if (invocation == null || invocation.getId() == null) {
                return;
            }
            boolean completed = RuntimeBackgroundTask.STATUS_COMPLETED.equals(task.getStatus());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", AgentDispatchTool.NAME);
            data.put("ok", completed);
            data.put("status", task.getStatus());
            data.put("agentId", task.getAgentId());
            data.put("agentType", task.getAgentType());
            data.put("childSessionId", task.getChildSessionId());
            data.put("description", task.getDescription());
            data.put("content", task.getOutput());
            data.put("totalToolUseCount", task.getTotalToolUseCount());
            data.put("totalDurationMs", task.getTotalDurationMs());
            data.put("run_in_background", true);
            if (StringUtils.isNotBlank(task.getErrorMsg())) {
                data.put("errorMsg", task.getErrorMsg());
            }
            String observation = ToolObservationSerializer.serializeSuccess(data);
            recorder.finishToolInvocation(ToolInvocationFinishRecord.builder()
                    .toolInvocationId(invocation.getId())
                    .runId(run.getId())
                    .requestId(task.getParentRequestId())
                    .sessionId(task.getParentSessionId())
                    .toolCallId(task.getParentToolUseId())
                    .toolName(AgentDispatchTool.NAME)
                    .status(completed
                            ? ExecutionLedgerConstants.STATUS_SUCCESS
                            : ExecutionLedgerConstants.STATUS_FAILED)
                    .llmObservation(observation)
                    .toolResult(observation)
                    .errorMsg(task.getErrorMsg())
                    .finishedAt(resolveFinishedAt(task))
                    .build());
        } catch (Exception e) {
            log.warn("persisted subagent parent ledger settle failed taskId={}", task.getId(), e);
        }
    }

    private static LocalDateTime resolveFinishedAt(RuntimeBackgroundTask task) {
        if (task != null && task.getEndedAtMs() != null) {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(task.getEndedAtMs()),
                    ZoneId.systemDefault());
        }
        return LocalDateTime.now();
    }

    private static void emitBackgroundSettlement(AgentContext parent,
                                                 String parentToolUseId,
                                                 String parentToolName,
                                                 String description,
                                                 String prompt,
                                                 String subagentType,
                                                 SubAgentResult result,
                                                 boolean completed,
                                                 ActiveAgentRunRegistry runRegistry,
                                                 String childSessionId,
                                                 boolean settleAllowed) {
        if (parent == null) {
            return;
        }
        Printer printer = parent.getPrinter();
        if (printer == null) {
            return;
        }
        String toolName = StringUtils.defaultIfBlank(parentToolName, AgentDispatchTool.NAME);
        try {
            if (StringUtils.isNotBlank(parentToolUseId)) {
                Map<String, Object> data = result == null
                        ? new LinkedHashMap<>()
                        : SubAgentObservationBuilder.build(result, toolName, true);
                data.put("tool", toolName);
                data.put("ok", completed);
                data.put("run_in_background", true);
                if (result == null) {
                    data.put("status", completed
                            ? SubAgentResult.STATUS_COMPLETED
                            : SubAgentResult.STATUS_FAILED);
                    data.put("description", description);
                    data.put("agentType", subagentType);
                    if (StringUtils.isNotBlank(childSessionId)) {
                        data.put("childSessionId", childSessionId);
                    }
                } else if (!completed && StringUtils.isBlank(String.valueOf(data.get("status")))) {
                    data.put("status", SubAgentResult.STATUS_FAILED);
                }

                Map<String, Object> toolParam = new LinkedHashMap<>();
                toolParam.put("description", description);
                toolParam.put("prompt", prompt);
                toolParam.put("subagent_type",
                        StringUtils.defaultIfBlank(subagentType, SubAgentRegistry.TYPE_GENERAL_PURPOSE));
                toolParam.put("run_in_background", true);

                String observation = ToolObservationSerializer.serializeSuccess(data);
                ToolResultStreamPayload toolResult = ToolResultStreamPayload.builder()
                        .toolName(toolName)
                        .toolCallId(parentToolUseId)
                        .toolParam(toolParam)
                        .toolResult(observation)
                        .build();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("run_in_background", true);
                extra.put("status", completed ? "success" : "failed");
                extra.put("isFinal", true);
                printer.send(parentToolUseId, "tool_result", toolResult, extra, null, true);
            }
            if (settleAllowed && shouldReleaseParentRun(parent)) {
                // 没有 wake port 时 Inbox 无法自动续跑；此时必须关流，避免客户端永久等待。
                if (shouldSettleParentStream(parent) || backgroundWakePort(parent) == null) {
                    Map<String, Object> settle = new LinkedHashMap<>();
                    settle.put("reason", "background_idle");
                    printer.send("stream_settle", settle);
                }
                if (runRegistry != null && StringUtils.isNotBlank(parent.getRequestId())) {
                    runRegistry.end(parent.getRequestId());
                    notifyRunSettled(parent);
                }
            }
        } catch (Exception e) {
            log.debug("background settlement emit skipped: {}", e.getMessage());
        }
    }

    private static void notifyRunSettled(AgentContext parent) {
        BackgroundTaskWakePort wakePort = backgroundWakePort(parent);
        if (wakePort == null) {
            return;
        }
        try {
            wakePort.onRunSettled(parent.getSessionId(), parent.getRequestId());
        } catch (Exception e) {
            log.warn("background settlement wake check failed sessionId={} requestId={}",
                    parent.getSessionId(), parent.getRequestId(), e);
        }
    }

    private static BackgroundTaskWakePort backgroundWakePort(AgentContext parent) {
        CuteRuntimeDependencies deps = parent == null ? null : parent.getRuntimeDependencies();
        return deps == null ? null : deps.getOptionalBackgroundTaskWakePort();
    }

    /** 已有同 agentId 的后台 run 未收尾：返回可重试的业务失败，不再叠加第二条 run。 */
    private static Map<String, Object> softBusy(String toolName, String agentId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("ok", false);
        data.put("status", RuntimeBackgroundTask.STATUS_RUNNING);
        data.put("agentId", agentId);
        data.put("message", "该子 Agent 正在启动或收尾，请稍后重试。");
        return data;
    }

    private static Map<String, Object> rejectedData(String toolName,
                                                    String agentId,
                                                    String childSessionId,
                                                    String description,
                                                    String subagentType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("ok", false);
        data.put("status", RuntimeBackgroundTask.STATUS_FAILED);
        data.put("agentId", agentId);
        data.put("childSessionId", childSessionId);
        data.put("task_type", RuntimeBackgroundTask.TYPE_LOCAL_AGENT);
        data.put("description", description);
        data.put("agentType", StringUtils.defaultIfBlank(subagentType, SubAgentRegistry.TYPE_GENERAL_PURPOSE));
        data.put("errorMsg", "background executor rejected");
        return data;
    }

    private static Map<String, Object> quotaDeniedData(String toolName,
                                                       String agentId,
                                                       String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("ok", false);
        data.put("status", RuntimeBackgroundTask.STATUS_FAILED);
        data.put("agentId", agentId);
        data.put("errorMsg", reason);
        return data;
    }

    private static String trimToString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
