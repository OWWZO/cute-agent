package org.wwz.ai.domain.agent.runtime.subagent;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionIdentity;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionStreamPort;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.memory.WorkingMemoryScopes;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ReactImplAgent;
import org.wwz.ai.domain.agent.runtime.agent.ReactFinalAnswerResolver;
import org.wwz.ai.domain.agent.runtime.artifact.TaskSummaryArtifactProtocol;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactBinding;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.TaskSummaryResult;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.prompt.AgentExecutionRole;
import org.wwz.ai.domain.agent.runtime.prompt.PromptAssembler;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Agent 执行引擎。
 * 阻塞跑完嵌套 ReactImplAgent（LLM 侧流式），只把结论文本回传主 Agent。
 * 结束后将 Memory 投影到子会话自己的 working_memory（scope={@code main}），支持再次唤醒。
 */
@Slf4j
@Component
public class SubAgentRunner {

    /** 长 thinking / 工具静默时向前端发心跳，避免 UI 假死感（对标 onQueryProgress）。 */
    private static final long PROGRESS_HEARTBEAT_SECONDS = 30L;

    /** 定时调度器保留单平台线程；它只投递心跳，不承载阻塞 I/O。 */
    private static final ScheduledExecutorService PROGRESS_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "subagent-progress");
                thread.setDaemon(true);
                return thread;
            });

    private final SubAgentRegistry registry;
    private final SubAgentConcurrencyGate concurrencyGate;
    private SessionWorkingMemoryService sessionWorkingMemoryService;

    public SubAgentRunner(SubAgentRegistry registry) {
        this(registry, SubAgentConcurrencyGate.defaults());
    }

    @Autowired
    public SubAgentRunner(SubAgentRegistry registry, SubAgentConcurrencyGate concurrencyGate) {
        this.registry = registry;
        this.concurrencyGate = concurrencyGate == null
                ? SubAgentConcurrencyGate.defaults()
                : concurrencyGate;
    }

    @Autowired(required = false)
    public void setSessionWorkingMemoryService(SessionWorkingMemoryService sessionWorkingMemoryService) {
        this.sessionWorkingMemoryService = sessionWorkingMemoryService;
    }

    /**
     * 子 Agent 是否已有可唤醒的工作记忆。
     * <p>记忆按 {@code (childSessionId, main)} 存——子会话与主会话同构，
     * 不再挂在父 sessionId 的 {@code sub:{agentId}} 作用域下。</p>
     */
    public boolean hasPersistedMemory(String childSessionId) {
        if (sessionWorkingMemoryService == null || StringUtils.isBlank(childSessionId)) {
            return false;
        }
        String agentId = extractAgentIdFromChildSessionId(childSessionId);
        List<Message> prior = sessionWorkingMemoryService.loadReadyMessages(
                childSessionId,
                WorkingMemoryScopes.MAIN,
                SubAgentContextFactory.newChildRequestId(agentId));
        return prior != null && !prior.isEmpty();
    }

    /** {@code sub_{agentId}} → {@code agentId}；非该前缀时原样返回。 */
    private static String extractAgentIdFromChildSessionId(String childSessionId) {
        String prefix = SubAgentContextFactory.CHILD_SESSION_ID_PREFIX;
        return childSessionId.startsWith(prefix)
                ? childSessionId.substring(prefix.length())
                : childSessionId;
    }

    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType) {
        return run(parentContext, description, prompt, subagentType, null, null, null);
    }

    /**
     * @param resumeAgentId 非空时从子会话的 working_memory hydrate 并续跑同一 agentId
     */
    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType,
                              String resumeAgentId) {
        return run(parentContext, description, prompt, subagentType, resumeAgentId, null, null);
    }

    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType,
                              String resumeAgentId,
                              RunCancellation cancellationOverride) {
        return run(parentContext, description, prompt, subagentType, resumeAgentId, cancellationOverride, null);
    }

    /**
     * @param resumeAgentId         非空时续跑
     * @param cancellationOverride  非空时覆盖子上下文取消令牌（后台 Agent 用任务级令牌）
     * @param preferredAgentId      后台预分配 agentId（非 resume 时优先使用，便于 SendMessage 寻址）
     */
    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType,
                              String resumeAgentId,
                              RunCancellation cancellationOverride,
                              String preferredAgentId) {
        return run(parentContext, description, prompt, subagentType, resumeAgentId,
                cancellationOverride, preferredAgentId, null);
    }

    /**
     * @param explicitParentToolUseId 调用方在父线程捕获的 Agent 工具 toolCallId。
     *                                异步派发必须显式传入，不能依赖后台线程的 ThreadLocal。
     */
    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType,
                              String resumeAgentId,
                              RunCancellation cancellationOverride,
                              String preferredAgentId,
                              String explicitParentToolUseId) {
        return run(parentContext, description, prompt, subagentType, resumeAgentId,
                cancellationOverride, preferredAgentId, explicitParentToolUseId, null);
    }

    public SubAgentResult run(AgentContext parentContext,
                              String description,
                              String prompt,
                              String subagentType,
                              String resumeAgentId,
                              RunCancellation cancellationOverride,
                              String preferredAgentId,
                              String explicitParentToolUseId,
                              String logicalRunId) {
        long start = System.currentTimeMillis();
        boolean resume = StringUtils.isNotBlank(resumeAgentId);
        String agentId;
        if (resume) {
            agentId = resumeAgentId.trim();
        } else if (StringUtils.isNotBlank(preferredAgentId)) {
            agentId = preferredAgentId.trim();
        } else {
            agentId = SubAgentContextFactory.newAgentId();
        }
        String effectiveType = subagentType;
        SubAgentDefinition definition = registry.resolveOrDefault(effectiveType);
        // 确定性子会话 ID：续跑复用同一个，首跑由 agentId 派生（见 SubAgentContextFactory#newChildSessionId）
        String childSessionId = SubAgentContextFactory.newChildSessionId(agentId);

        if (parentContext == null) {
            return failed(agentId, childSessionId, definition, description, prompt, start, "parent AgentContext 为空");
        }
        int childDepth = SubAgentContextFactory.childDelegationDepth(parentContext);
        if (childDepth > SubAgentTreeLimits.MAX_DELEGATION_DEPTH) {
            return failed(agentId, childSessionId, definition, description, prompt, start,
                    "子 Agent 深度上限为 " + SubAgentTreeLimits.MAX_DELEGATION_DEPTH);
        }
        if (StringUtils.isBlank(prompt)) {
            return failed(agentId, childSessionId, definition, description, prompt, start, "prompt 不能为空");
        }
        if (parentContext.isRunCancelled()) {
            recordCancelledBeforeStart(parentContext, description, prompt, definition, agentId,
                    childSessionId, cancellationOverride);
            return failed(agentId, childSessionId, definition, description, prompt, start,
                    "父 run 已停止，取消子 Agent 启动");
        }
        if (parentContext.getToolCollection() == null
                && parentContext.getSubAgentToolCollection() == null) {
            return failed(agentId, childSessionId, definition, description, prompt, start, "父 Agent 工具池为空");
        }

        if (resume && sessionWorkingMemoryService == null) {
            return failed(agentId, childSessionId, definition, description, prompt, start,
                    "无法唤醒子 Agent：SessionWorkingMemoryService 未注入");
        }

        boolean background = cancellationOverride != null;
        String rootSessionId = SubAgentContextFactory.rootSessionId(parentContext);
        if (!background) {
            RuntimeBackgroundTaskRegistry.TreeQuotaDecision quota =
                    parentContext.requireBackgroundTasks().reserveTreeAgent(
                            rootSessionId,
                            SubAgentContextFactory.currentAgentId(parentContext),
                            agentId);
            if (!quota.allowed()) {
                return failed(agentId, childSessionId, definition, description, prompt, start,
                        quota.reason());
            }
        }
        try {
            SubAgentResult gated;
            if (SubAgentExecutionScope.isInline()) {
                // task 池已拒绝并 CallerRuns：当前线程没有 gate permit，也不能再抢第二把。
                gated = runUnlocked(parentContext, description, prompt, definition, agentId, childSessionId,
                        start, resume, cancellationOverride, explicitParentToolUseId, logicalRunId);
            } else {
                gated = concurrencyGate.runWithPermit(() ->
                        runUnlocked(parentContext, description, prompt, definition, agentId, childSessionId,
                                start, resume, cancellationOverride, explicitParentToolUseId, logicalRunId));
            }
            if (gated == null) {
                return failed(agentId, childSessionId, definition, description, prompt, start,
                        "子 Agent 并发已达上限(" + concurrencyGate.getMaxConcurrent()
                                + ")，等待 " + concurrencyGate.getAcquireTimeoutSeconds() + "s 仍无空闲许可");
            }
            return gated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(agentId, childSessionId, definition, description, prompt, start, "子 Agent 等待并发许可被中断");
        } finally {
            if (!background) {
                parentContext.requireBackgroundTasks().releaseTreeAgent(rootSessionId, agentId);
            }
        }
    }

    private SubAgentResult runUnlocked(AgentContext parentContext,
                                       String description,
                                       String prompt,
                                       SubAgentDefinition definition,
                                       String agentId,
                                       String childSessionId,
                                       long start,
                                       boolean resume,
                                       RunCancellation cancellationOverride,
                                       String explicitParentToolUseId,
                                       String logicalRunId) {
        ToolCollection parentToolCollection = parentContext.getSubAgentToolCollection() != null
                ? parentContext.getSubAgentToolCollection()
                : parentContext.getToolCollection();
        ToolCollection childTools = SubAgentToolFilter.filter(parentToolCollection, definition);
        String parentToolUseId = StringUtils.isNotBlank(explicitParentToolUseId)
                ? explicitParentToolUseId.trim()
                : resolveParentToolUseId(parentContext);
        if (StringUtils.isBlank(parentToolUseId)) {
            log.warn("{} subagent spawn without parentToolUseId type={} id={} — nested tools will not attach to Agent card",
                    parentContext.getRequestId(), definition.getAgentType(), agentId);
        }
        String sessionKey = StringUtils.defaultIfBlank(parentContext.getSessionId(), parentContext.getRequestId());
        // 原子占位必须早于所有 child 启动副作用：并发 resume/spawn 同一 agentId 时只有一个能进入。
        // 占位失败说明另一个实例仍持有该 agentId：本实例不启动子 Agent，
        // 但仍要落一条 FAILED 的 child run 并回传原因（否则调用方看到的是「什么都没发生」）。
        boolean claimed = SessionAgentMailboxHub.tryMarkActive(sessionKey, agentId);
        String rejectionReason = claimed ? null : "子 Agent 正在启动或收尾，请稍后重试 SendMessage";
        try {
            return runClaimed(parentContext, description, prompt, definition, agentId, childSessionId,
                    start, resume, cancellationOverride, parentToolUseId, childTools, logicalRunId, sessionKey,
                    rejectionReason);
        } finally {
            // 只有本次真正占位成功才允许释放：否则会清掉另一个运行实例的邮箱。
            if (claimed) {
                SessionAgentMailboxHub.markActive(sessionKey, agentId, false);
            }
        }
    }

    private SubAgentResult runClaimed(AgentContext parentContext,
                                      String description,
                                      String prompt,
                                      SubAgentDefinition definition,
                                      String agentId,
                                      String childSessionId,
                                      long start,
                                      boolean resume,
                                      RunCancellation cancellationOverride,
                                      String parentToolUseId,
                                      ToolCollection childTools,
                                      String logicalRunId,
                                      String sessionKey,
                                      String rejectionReason) {
        // 先落库后运行：子会话身份行必须在 child run 启动前写入（对齐 ZCode「持久化早于 Spawned 广播」）。
        // 落库失败即中止，避免无主运行与不可查询的孤儿会话；端口缺失时降级放行（仅告警）。
        if (!ensureChildSessionPersisted(parentContext, childSessionId, description, definition)) {
            return failed(agentId, childSessionId, definition, description, prompt, start,
                    "子会话身份落库失败，已中止子 Agent 启动");
        }

        // Phase 3 SSE 隔离：开关开启且端口可用时，子事件写子会话自己的投影流，
        // 父流只留 subagent_start / subagent_end 两条自包含帧。
        // 隔离失败（端口返回 null）时回退旧的 SubAgentPrinter 透传父流行为，保证不破线。
        String childRequestId = SubAgentContextFactory.newChildRequestId(agentId);
        Printer isolatedChildPrinter = openIsolatedChildStream(
                parentContext, childSessionId, childRequestId, prompt);
        Printer childEventPrinter = WorkspaceFileEventPrinter.wrap(
                isolatedChildPrinter,
                parentContext.getPrinter(),
                SubAgentContextFactory.rootSessionId(parentContext),
                childSessionId,
                childRequestId,
                parentToolUseId,
                agentId,
                definition.getAgentType());

        final AgentContext childContext;
        try {
            childContext = SubAgentContextFactory.create(
                    parentContext, prompt, description, childTools, agentId, definition.getAgentType(),
                    parentToolUseId, childEventPrinter,
                    childEventPrinter == null ? null : childRequestId);
            childContext.setTreeIdentity(
                    childContext.getRootSessionId(),
                    childContext.getDelegationDepth(),
                    childContext.getParentAgentId(),
                    logicalRunId);
            childContext.setToolScopeApplied(Boolean.TRUE);
            // 后台 Agent：用任务级取消令牌，TaskStop 不误伤主 run；同步派发仍共享父令牌
            if (cancellationOverride != null) {
                childContext.control().bindCancellation(cancellationOverride);
            }
            // 子 Agent 记账落独立 run：LLM / tool / artifact 挂到 childSessionId 的 run，
            // 父 run 的 llm_call_count / tool_call_count 不再包含子代理调用。
            // recorder 共享同一无状态实例，run 身份由 AgentRunState 隔离。
            ExecutionLedgerRunSupport.initializeSubAgentRun(
                    childContext.getExecutionRecorder(),
                    childContext,
                    ExecutionLedgerConstants.ENTRY_AGENT_SUBAGENT,
                    prompt);
            // ActiveRun 是取消/并发判活的事实来源，不能依赖 SSE 隔离开关。
            registerChildRun(parentContext, childContext, childSessionId, childRequestId);
        } catch (RuntimeException e) {
            // startup 广播前失败时子流已在注册表中，必须立即注销，避免留下无法查询的孤儿投影。
            if (isolatedChildPrinter != null) {
                closeIsolatedChildStream(parentContext, childSessionId, isolatedChildPrinter);
            }
            throw e;
        }
        if (isolatedChildPrinter != null) {
            emitParentSubAgentFrame(parentContext, "subagent_start", childSessionId, childRequestId,
                    parentToolUseId, agentId, definition.getAgentType(), description, "running", null);
        }
        ReactImplAgent agent = null;
        // 子 run 终态收口：resume 早退、异常与正常路径都要收敛到同一个 finally，
        // 避免漏写导致 run 永挂 RUNNING、子会话投影流泄漏。
        java.util.concurrent.atomic.AtomicInteger childRunStatus =
                new java.util.concurrent.atomic.AtomicInteger(ExecutionLedgerConstants.STATUS_FAILED);
        java.util.concurrent.atomic.AtomicReference<String> childRunSummary =
                new java.util.concurrent.atomic.AtomicReference<>("");
        AtomicBoolean waitingForBackground = new AtomicBoolean(false);
        try {
            childContext.bindPendingInjectQueue(SessionAgentMailboxHub.queue(sessionKey, agentId));

            // 占位失败：另一个实例仍在跑同一 agentId，本次不启动子 Agent，
            // 但必须走这里的 finally 把已落库的 child run 收成 FAILED。
            if (rejectionReason != null) {
                childRunSummary.set(rejectionReason);
                return failed(agentId, childSessionId, definition, description, prompt, start,
                        rejectionReason, null, false);
            }

            // 记忆键统一为 (childSessionId, main)：子会话与主会话同构，用户从会话树点进来
            // 续聊时读到的是同一份记忆（见 D11/D15）。
            childContext.setMemoryScope(WorkingMemoryScopes.MAIN);

            // resume：每次运行需要唯一 requestId，否则 working_memory persist 幂等跳过
            if (resume) {
                if (isSubAgentStillRunning(parentContext, agentId, logicalRunId)) {
                    return failed(agentId, childSessionId, definition, description, prompt, start,
                            "目标 agentId 仍有另一条运行中的任务，请等待其结束或先用 TaskStop 停止后再唤醒",
                            null, false);
                }
                // 与首跑统一短 requestId；父 requestId 仅用于日志
                // 隔离流路径下 requestId 已在建流时定死，不能重新分配，否则投影与 ledger run 对不上；
                // 仅旧透传路径仍按原逻辑换新 requestId，保证 working_memory persist 不被幂等跳过。
                if (isolatedChildPrinter == null) {
                    childContext.setRequestId(SubAgentContextFactory.newChildRequestId(agentId));
                }
                String resumeRequestId = childContext.getRequestId();
                List<Message> prior = sessionWorkingMemoryService.loadReadyMessages(
                        childContext.getSessionId(), WorkingMemoryScopes.MAIN, resumeRequestId);
                if (prior == null || prior.isEmpty()) {
                    return failed(agentId, childSessionId, definition, description, prompt, start,
                            "无法唤醒子 Agent：未找到 agentId=" + agentId + " 的工作记忆（可能已过期或从未成功结束）",
                            null, false);
                }
                childContext.setWorkingMemoryMessages(new ArrayList<>(prior));
                log.info("{} resume subagent type={} id={} priorMsgs={} childRequestId={} parentRequestId={}",
                        parentContext.getRequestId(), definition.getAgentType(), agentId, prior.size(),
                        resumeRequestId, parentContext.getRequestId());
            } else {
                childContext.setWorkingMemoryMessages(null);
                log.info("{} spawn subagent type={} id={} childRequestId={} parentRequestId={}",
                        parentContext.getRequestId(), definition.getAgentType(), agentId,
                        childContext.getRequestId(), parentContext.getRequestId());
            }

            // 工具全部无状态：context 由 execute(input, context) 传入，实例可跨子 Agent 共享，
            // 只需把 collection 自身绑到子 context（MCP 执行器等由它转发）。
            childTools.setAgentContext(childContext);

            SessionAgentMailboxHub.markActive(sessionKey, agentId, true);
            childContext.setResolvedSystemPrompt(PromptAssembler.assembleForContext(
                    childContext,
                    AgentExecutionRole.WORKER,
                    definition.getAgentType(),
                    definition.getSystemPrompt(),
                    null).systemPrompt());
            agent = new ReactImplAgent(childContext);
            agent.setName("subagent:" + definition.getAgentType());
            agent.setDescription(StringUtils.defaultIfBlank(description, definition.getAgentType()));
            // 子 agent 与主 agent 复用同一套行模型：正文 delta 同样走 agent_stream，
            // 由隔离子会话流（openIsolatedChildStream）承载、子会话面板用同一条 combineData
            // 链路消费；透传回退路径由 SubAgentPrinter 打嵌套标签、前端挂嵌套时间线。
            // 返回值回给协调者是消费语义，不改变展示语义。
            if (definition.getMaxSteps() != null && definition.getMaxSteps() > 0) {
                agent.setMaxSteps(definition.getMaxSteps());
            }

            log.info("{} {} streaming subagent type={} id={} desc={}",
                    parentContext.getRequestId(),
                    resume ? "resume" : "spawn",
                    definition.getAgentType(), agentId, description);
            AtomicBoolean finished = new AtomicBoolean(false);
            ScheduledFuture<?> heartbeat = PROGRESS_SCHEDULER.scheduleAtFixedRate(
                    () -> emitSubAgentProgress(childContext, agentId, definition.getAgentType(), description, start, finished),
                    PROGRESS_HEARTBEAT_SECONDS,
                    PROGRESS_HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS);
            try {
                String runResult = agent.run(prompt);
                finished.set(true);
                if (childContext.isRunCancelled()) {
                    // 取消后即使模型正常返回，也不能把本轮记成 COMPLETED/SUCCESS；
                    // 否则 finally 会把账本改写成 STOPPED，而返回给父 Agent 的结果仍是成功。
                    int toolUseCount = countToolUses(agent);
                    boolean memoryPersisted = persistSubWorkingMemory(childContext, parentContext, agent, definition);
                    String content = emitSubAgentFinalReply(childContext, agent, buildResumeHintContent(
                            "Terminated: 用户已停止本轮执行", agentId, memoryPersisted));
                    childRunStatus.set(ExecutionLedgerConstants.STATUS_STOPPED);
                    childRunSummary.set(content);
                    return failed(agentId, childSessionId, definition, description, prompt, start,
                            "用户已停止本轮执行", content, memoryPersisted);
                }
                boolean thinkFailed = isThinkFailed(agent, runResult);
                int toolUseCount = countToolUses(agent);
                // 成功/think 失败都落库，便于主 Agent resume
                boolean memoryPersisted = persistSubWorkingMemory(childContext, parentContext, agent, definition);
                markConsumedNotifications(childContext, memoryPersisted);
                String content;
                if (thinkFailed) {
                    String reason = StringUtils.defaultIfBlank(
                            agent.getThinkFailureReason(), extractThinkFailedReason(runResult));
                    content = buildResumeHintContent(
                            "Terminated: LLM think failed: " + reason, agentId, memoryPersisted);
                    content = emitSubAgentFinalReply(childContext, agent, content);
                    return SubAgentResult.builder()
                            .status(SubAgentResult.STATUS_FAILED)
                            .agentId(agentId)
                            .childSessionId(childSessionId)
                            .agentType(definition.getAgentType())
                            .description(description)
                            .prompt(prompt)
                            .content(content)
                            .totalToolUseCount(toolUseCount)
                            .totalDurationMs(System.currentTimeMillis() - start)
                            .errorMsg("LLM think failed: " + reason
                                    + "；请用 SendMessage(to=\"" + agentId + "\", message=…) 续跑"
                                    + (memoryPersisted ? "" : "（注意：工作记忆未落库，resume 可能失败）"))
                            .memoryPersisted(memoryPersisted)
                            .build();
                }
                content = ReactFinalAnswerResolver.resolve(agent, runResult);
                if (hasOutstandingOwnedWork(childContext, agentId)) {
                    if (logicalRunId != null) {
                        // 候选答复只进入子会话历史；逻辑任务仍保持 running，父 Agent 看不到它。
                        content = emitSubAgentFinalReply(childContext, agent, content);
                        childContext.requireBackgroundTasks().markWaiting(logicalRunId);
                        waitingForBackground.set(true);
                        childRunStatus.set(ExecutionLedgerConstants.STATUS_SUCCESS);
                        childRunSummary.set(content);
                        return SubAgentResult.builder()
                                .status(SubAgentResult.STATUS_WAITING)
                                .agentId(agentId)
                                .childSessionId(childSessionId)
                                .agentType(definition.getAgentType())
                                .description(description)
                                .prompt(prompt)
                                .content(content)
                                .totalToolUseCount(toolUseCount)
                                .totalDurationMs(System.currentTimeMillis() - start)
                                .memoryPersisted(memoryPersisted)
                                .build();
                    }
                    ForegroundDrainResult drained = drainForegroundUntilQuiet(
                            childContext, parentContext, agent, definition, agentId);
                    memoryPersisted = drained.memoryPersisted();
                    content = drained.content();
                    toolUseCount = drained.toolUseCount();
                    if (!drained.quiet()) {
                        childRunStatus.set(ExecutionLedgerConstants.STATUS_FAILED);
                        childRunSummary.set(content);
                        return failed(agentId, childSessionId, definition, description, prompt, start,
                                "子 Agent 的后台工作未在收尾轮上限内完成",
                                content, memoryPersisted);
                    }
                }
                content = emitSubAgentFinalReply(childContext, agent, content);
                childRunStatus.set(ExecutionLedgerConstants.STATUS_SUCCESS);
                childRunSummary.set(content);
                return SubAgentResult.builder()
                        .status(SubAgentResult.STATUS_COMPLETED)
                        .agentId(agentId)
                        .childSessionId(childSessionId)
                        .agentType(definition.getAgentType())
                        .description(description)
                        .prompt(prompt)
                        .content(content)
                        .totalToolUseCount(toolUseCount)
                        .totalDurationMs(System.currentTimeMillis() - start)
                        .memoryPersisted(memoryPersisted)
                        .build();
            } finally {
                finished.set(true);
                heartbeat.cancel(false);
            }
        } catch (Exception e) {
            log.error("{} streaming subagent failed type={} id={}",
                    parentContext.getRequestId(), definition.getAgentType(), agentId, e);
            boolean memoryPersisted = persistSubWorkingMemory(childContext, parentContext, agent, definition);
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String content = buildResumeHintContent(
                    "Terminated: subagent failed: " + err, agentId, memoryPersisted);
            content = emitSubAgentFinalReply(childContext, agent, content);
            childRunStatus.set(ExecutionLedgerConstants.STATUS_FAILED);
            childRunSummary.set(err);
            return failed(agentId, childSessionId, definition, description, prompt, start, err, content, memoryPersisted);
        } finally {
            ActiveAgentRunRegistry activeRunRegistry = parentContext.activeAgentRunRegistry();
            if (activeRunRegistry != null) {
                activeRunRegistry.end(childRequestId);
            }
            // 子 run 终态收口（含取消：/stop 走异常或正常路径都已覆盖）
            try {
                if (childContext.isRunCancelled()) {
                    childRunStatus.set(ExecutionLedgerConstants.STATUS_STOPPED);
                }
                ExecutionLedgerRunSupport.finishRun(
                        childContext,
                        childRunStatus.get(),
                        childRunSummary.get(),
                        null,
                        null);
            } catch (Exception ledgerError) {
                // 子 run 记账失败不得影响子 Agent 结果回传
                log.warn("{} subagent run finish failed id={}: {}",
                        parentContext.getRequestId(), agentId, ledgerError.getMessage());
            }
            // Phase 3：父流补一条自包含的 subagent_end，然后关闭子会话投影流。
            if (isolatedChildPrinter != null && !waitingForBackground.get()) {
                emitParentSubAgentFrame(parentContext, "subagent_end", childSessionId, childRequestId,
                        parentToolUseId, agentId, definition.getAgentType(), description,
                        childContext.isRunCancelled() ? "stopped" : resolveEndStatus(childRunStatus.get()),
                        resolveEndSummary(childRunStatus, childRunSummary));
                closeIsolatedChildStream(parentContext, childSessionId, isolatedChildPrinter);
            }
            // mailbox 占位由 runUnlocked 的外层 finally 统一释放（异常路径也覆盖）。
        }
    }

    private ForegroundDrainResult drainForegroundUntilQuiet(AgentContext childContext,
                                                            AgentContext parentContext,
                                                            ReactImplAgent agent,
                                                            SubAgentDefinition definition,
                                                            String agentId) {
        String content = "";
        int toolUseCount = countToolUses(agent);
        boolean memoryPersisted = persistSubWorkingMemory(childContext, parentContext, agent, definition);
        for (int closingTurn = 0; closingTurn < SubAgentTreeLimits.MAX_CLOSING_TURNS; closingTurn++) {
            if (childContext.isRunCancelled() || parentContext.isRunCancelled()) {
                return new ForegroundDrainResult(content, toolUseCount, memoryPersisted, true);
            }
            awaitOwnedBackgroundTasks(childContext, agentId);
            if (!hasOutstandingOwnedWork(childContext, agentId)) {
                return new ForegroundDrainResult(content, toolUseCount, memoryPersisted, true);
            }

            // 同一前台逻辑任务继续下一轮：先把本轮完整记忆作为下一轮前缀，
            // 再把后台通知在 BaseAgent 首步 drain 进上下文。
            childContext.setWorkingMemoryMessages(new ArrayList<>(agent.getMemory().getMessages()));
            String turnResult = agent.run("");
            toolUseCount = countToolUses(agent);
            memoryPersisted = persistSubWorkingMemory(childContext, parentContext, agent, definition);
            markConsumedNotifications(childContext, memoryPersisted);
            content = ReactFinalAnswerResolver.resolve(agent, turnResult);
        }
        return new ForegroundDrainResult(content, toolUseCount, memoryPersisted,
                !hasOutstandingOwnedWork(childContext, agentId));
    }

    private void awaitOwnedBackgroundTasks(AgentContext childContext, String agentId) {
        RuntimeBackgroundTaskRegistry registry = childContext.requireBackgroundTasks();
        for (RuntimeBackgroundTask task : registry.listRunningOwnedBy(agentId)) {
            registry.awaitUntilTerminal(task.getId(),
                    () -> childContext.isRunCancelled());
        }
    }

    private static boolean hasOutstandingOwnedWork(AgentContext childContext, String agentId) {
        RuntimeBackgroundTaskRegistry registry = childContext.requireBackgroundTasks();
        if (!registry.listRunningOwnedBy(agentId).isEmpty()) {
            return true;
        }
        if (registry.hasUnconsumedOwnedBy(agentId)) {
            return true;
        }
        String sessionKey = SessionBackgroundTaskHub.keyFor(
                childContext.getSessionId(), childContext.getRequestId());
        if (SessionNotificationHub.hasPending(sessionKey)) {
            return true;
        }
        var inbox = childContext.sessionInboxPort();
        return inbox != null
                && (inbox.hasDeliverableKind(childContext.getSessionId(),
                        org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem.KIND_BACKGROUND_NOTIFICATION)
                || inbox.hasDeliverableKind(childContext.getSessionId(),
                        org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem.KIND_USER_MESSAGE));
    }

    private static void markConsumedNotifications(AgentContext childContext, boolean memoryPersisted) {
        if (!memoryPersisted) {
            return;
        }
        childContext.requireBackgroundTasks().markNotified(
                childContext.control().drainConsumedBackgroundNotifications());
    }

    private record ForegroundDrainResult(String content,
                                         int toolUseCount,
                                         boolean memoryPersisted,
                                         boolean quiet) {
    }

    private static String resolveEndStatus(int runStatus) {
        return runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? "completed" : "failed";
    }

    private static void registerChildRun(AgentContext parentContext,
                                         AgentContext childContext,
                                         String childSessionId,
                                         String childRequestId) {
        ActiveAgentRunRegistry registry = parentContext.activeAgentRunRegistry();
        if (registry == null || childContext == null) {
            return;
        }
        registry.begin(childRequestId, childSessionId, parentContext.getUserId());
        registry.bindContext(childRequestId, childContext);
    }

    private static String resolveEndSummary(java.util.concurrent.atomic.AtomicInteger runStatus,
                                            java.util.concurrent.atomic.AtomicReference<String> runSummary) {
        if (runStatus.get() == ExecutionLedgerConstants.STATUS_SUCCESS) {
            return runSummary.get();
        }
        return runSummary.get();
    }

    /**
     * 打开子会话独立投影流（Phase 3）。
     * <p>
     * 只有开关开启、端口可用且拿到 Printer 时才算隔离成功；否则返回 null，
     * 调用方回退到旧的 SubAgentPrinter 透传父流行为。
     */
    private static Printer openIsolatedChildStream(AgentContext parentContext,
                                                   String childSessionId,
                                                   String childRequestId,
                                                   String prompt) {
        CuteRuntimeDependencies dependencies = parentContext == null
                ? null
                : parentContext.getRuntimeDependencies();
        if (dependencies == null || !dependencies.isSubAgentIsolatedSessionEnabled()) {
            return null;
        }
        SubAgentSessionStreamPort port = dependencies.getOptionalSubAgentSessionStreamPort();
        if (port == null) {
            log.warn("{} subagent isolated session port absent, fallback to parent passthrough childSessionId={}",
                    parentContext.getRequestId(), childSessionId);
            return null;
        }
        try {
            Printer printer = port.openChildSessionStream(
                    childSessionId,
                    parentContext.getUserId(),
                    childRequestId,
                    prompt,
                    parentContext.getAgentType());
            if (printer == null) {
                log.warn("{} open child session stream returned null, fallback childSessionId={}",
                        parentContext.getRequestId(), childSessionId);
            }
            return printer;
        } catch (Exception e) {
            log.error("{} open child session stream failed childSessionId={}, fallback",
                    parentContext.getRequestId(), childSessionId, e);
            return null;
        }
    }

    /**
     * 关闭子会话投影流。仅隔离路径调用；异常不影响主流程。
     */
    private static void closeIsolatedChildStream(AgentContext parentContext,
                                                 String childSessionId,
                                                 Printer isolatedChildPrinter) {
        if (isolatedChildPrinter == null) {
            return;
        }
        CuteRuntimeDependencies dependencies = parentContext == null
                ? null
                : parentContext.getRuntimeDependencies();
        SubAgentSessionStreamPort port = dependencies == null
                ? null
                : dependencies.getOptionalSubAgentSessionStreamPort();
        if (port == null) {
            return;
        }
        try {
            port.closeChildSessionStream(childSessionId, isolatedChildPrinter);
        } catch (Exception e) {
            log.warn("{} close child session stream failed childSessionId={}: {}",
                    parentContext == null ? "-" : parentContext.getRequestId(),
                    childSessionId, e.getMessage());
        }
    }

    /**
     * 父流子代理摘要帧（{@code subagent_start} / {@code subagent_end}）。
     * 只在隔离路径下发，复用既有 messageType 通道，不造新协议；
     * 帧内容自包含，不依赖子会话流。
     */
    private static void emitParentSubAgentFrame(AgentContext parentContext,
                                                String messageType,
                                                String childSessionId,
                                                String childRequestId,
                                                String parentToolUseId,
                                                String agentId,
                                                String agentType,
                                                String description,
                                                String status,
                                                String summary) {
        Printer parentPrinter = parentContext == null ? null : parentContext.getPrinter();
        if (parentPrinter == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("delegationId", agentId);
            payload.put("childSessionId", childSessionId);
            payload.put("childRequestId", childRequestId);
            payload.put("parentToolCallId", parentToolUseId);
            payload.put("subAgentId", agentId);
            payload.put("subagentType", agentType);
            payload.put("description", description);
            payload.put("status", status);
            if (summary != null) {
                payload.put("summary", summary);
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put(SubAgentPrinter.KEY_PARENT_TOOL_USE_ID, parentToolUseId);
            extra.put(SubAgentPrinter.KEY_SUB_AGENT_ID, agentId);
            extra.put(SubAgentPrinter.KEY_SUB_AGENT_TYPE, agentType);
            extra.put("childSessionId", childSessionId);
            extra.put("childRequestId", childRequestId);
            parentPrinter.send(null, messageType, payload, extra, null, true);
        } catch (Exception e) {
            log.debug("{} emit {} skipped id={}: {}",
                    parentContext.getRequestId(), messageType, agentId, e.getMessage());
        }
    }

    /**
     * 确保子会话身份行已落库（幂等）。端口缺失时降级放行并告警，不阻断子 Agent。
     *
     * @return true 表示可以继续启动 child run
     */
    private static boolean ensureChildSessionPersisted(AgentContext parentContext,
                                                       String childSessionId,
                                                       String description,
                                                       SubAgentDefinition definition) {
        SubAgentSessionPort port = parentContext == null || parentContext.getRuntimeDependencies() == null
                ? null
                : parentContext.getRuntimeDependencies().getOptionalSubAgentSessionPort();
        if (port == null) {
            log.warn("{} subagent child session port absent, skip persist childSessionId={}",
                    parentContext == null ? "-" : parentContext.getRequestId(), childSessionId);
            return true;
        }
        String title = StringUtils.defaultIfBlank(description,
                definition == null ? null : definition.getAgentType());
        try {
            port.ensureChildSession(SubAgentSessionIdentity.builder()
                    .childSessionId(childSessionId)
                    .parentSessionId(parentContext.getSessionId())
                    .userId(parentContext.getUserId())
                    .title(title)
                    .subAgentType(definition == null ? null : definition.getAgentType())
                    .build());
            return true;
        } catch (Exception e) {
            log.error("{} subagent child session persist failed childSessionId={} parentSessionId={}",
                    parentContext.getRequestId(), childSessionId, parentContext.getSessionId(), e);
            return false;
        }
    }

    /**
     * 子 Agent 工作记忆落库。
     * <p>
     * 作用域键是 <b>childSessionId</b>，scope 为 {@code main}：
     * {@link #hasPersistedMemory} / resume 分支都按同一子会话寻址。
     * 子会话自己的 ledger run 与 working_memory 行使用相同会话键。
     *
     * @return true 若成功写入至少一条投影
     */
    private boolean persistSubWorkingMemory(AgentContext childContext,
                                            AgentContext parentContext,
                                            ReactImplAgent agent,
                                            SubAgentDefinition definition) {
        if (sessionWorkingMemoryService == null || childContext == null || agent == null) {
            log.warn("{} skip persist sub WM: service/agent missing scope={}",
                    childContext == null ? "-" : childContext.getRequestId(),
                    childContext == null ? "-" : childContext.resolveMemoryScope());
            return false;
        }
        // 记忆键统一为 (childSessionId, main)：子会话是独立会话，不该把记忆写进父会话名下。
        // 模式 1（父派发）与模式 2（用户直聊）读写同一份记忆，这正是「同一个会话两种驱动方式」。
        String workingMemorySessionId = childContext.getSessionId();
        try {
            List<Message> delta = agent.exportWorkingMemoryDelta();
            List<Message> toPersist = delta;
            boolean fullSnapshot = false;
            if (toPersist == null || toPersist.isEmpty()) {
                // mid-run compact 后 delta 可能为空：全量兜底，避免 resume 空记忆
                List<Message> all = agent.getMemory() == null ? null : agent.getMemory().getMessages();
                if (all == null || all.isEmpty()) {
                    log.info("{} skip persist sub WM empty memory scope={} agentId={}",
                            childContext.getRequestId(), childContext.resolveMemoryScope(),
                            childContext.getSubAgentId());
                    return false;
                }
                toPersist = new ArrayList<>(all);
                fullSnapshot = true;
            }
            Long runId = childContext.getAgentRunState() == null
                    ? null
                    : childContext.getAgentRunState().getRunId();
            String entry = "sub_" + StringUtils.defaultIfBlank(
                    definition == null ? null : definition.getAgentType(), "agent");
            String scope = childContext.resolveMemoryScope();
            if (fullSnapshot) {
                String snapRequestId = SubAgentContextFactory.newChildRequestId(childContext.getSubAgentId());
                sessionWorkingMemoryService.replaceReadyProjection(
                        workingMemorySessionId,
                        scope,
                        snapRequestId,
                        toPersist);
                log.info("{} persisted sub working memory scope={} msgs={} fullSnapshot=true "
                                + "requestId={} persistOk=true",
                        childContext.getRequestId(), scope, toPersist.size(), snapRequestId);
            } else {
                sessionWorkingMemoryService.persistTurn(
                        workingMemorySessionId,
                        scope,
                        childContext.getRequestId(),
                        runId,
                        entry,
                        toPersist);
                log.info("{} persisted sub working memory scope={} msgs={} fullSnapshot=false "
                                + "requestId={} persistOk=true",
                        childContext.getRequestId(), scope, toPersist.size(), childContext.getRequestId());
            }
            return true;
        } catch (Exception e) {
            log.warn("{} persist sub working memory failed persistOk=false: {}",
                    childContext.getRequestId(), e.getMessage());
            return false;
        }
    }

    /**
     * 是否仍有同 agentId 的后台任务在跑。
     * <p>mailbox 占位由调用方 {@code tryMarkActive} 原子取得，这里不能再查 active——
     * 那会把当前这次运行自己误判成「仍在运行」。</p>
     */
    private static boolean isSubAgentStillRunning(AgentContext parent,
                                                  String agentId,
                                                  String excludeTaskId) {
        if (StringUtils.isBlank(agentId)) {
            return false;
        }
        RuntimeBackgroundTaskRegistry registry = parent == null ? null : parent.sessionControl().getBackgroundTasks();
        if (registry == null) {
            return false;
        }
        for (RuntimeBackgroundTask task : registry.listRunning()) {
            if (task == null
                    || !agentId.equals(StringUtils.trimToEmpty(task.getAgentId()))) {
                continue;
            }
            if (excludeTaskId != null && excludeTaskId.equals(task.getId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isThinkFailed(ReactImplAgent agent, String runResult) {
        if (agent != null && agent.getState() == AgentState.ERROR) {
            return true;
        }
        return StringUtils.isNotBlank(runResult) && runResult.startsWith("Terminated: LLM think failed");
    }

    private static String extractThinkFailedReason(String runResult) {
        if (StringUtils.isBlank(runResult)) {
            return "unknown";
        }
        String prefix = "Terminated: LLM think failed:";
        if (runResult.startsWith(prefix)) {
            return runResult.substring(prefix.length()).trim();
        }
        return runResult.trim();
    }

    private static String buildResumeHintContent(String head, String agentId, boolean memoryPersisted) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(head)) {
            sb.append(head.trim());
        }
        sb.append("\n\n请使用 SendMessage(to=\"")
                .append(StringUtils.defaultString(agentId))
                .append("\", message=…) 唤醒该子 Agent 继续任务");
        if (memoryPersisted) {
            sb.append("（工作记忆已尽量保留）。");
        } else {
            sb.append("（注意：本轮工作记忆未成功落库，resume 可能失败）。");
        }
        return sb.toString();
    }

    /**
     * 子 Agent 与主 Agent 共用终答收口协议，但不结束父 run。
     * 子上下文共享 artifact registry，因此只挑出当前子 Agent 实际调用过的工具产物。
     */
    private static String emitSubAgentFinalReply(
            AgentContext childContext,
            ReactImplAgent agent,
            String content
    ) {
        String fallback = StringUtils.defaultString(content);
        if (childContext == null
                || childContext.getPrinter() == null
                || StringUtils.isBlank(childContext.getParentToolUseId())) {
            return fallback;
        }
        try {
            List<ToolArtifactBinding> visibleArtifacts = collectSubAgentVisibleArtifacts(childContext, agent);
            TaskSummaryResult result = TaskSummaryArtifactProtocol.resolveForDelivery(
                    fallback,
                    visibleArtifacts
            );
            String summary = StringUtils.defaultString(result.getTaskSummary());
            childContext.getPrinter().send(
                    "result",
                    TaskSummaryArtifactProtocol.toEventPayload(result, visibleArtifacts)
            );
            return summary;
        } catch (Exception e) {
            log.debug("subagent final reply skipped id={}: {}", childContext.getSubAgentId(), e.getMessage());
            return fallback;
        }
    }

    private static List<ToolArtifactBinding> collectSubAgentVisibleArtifacts(
            AgentContext childContext,
            ReactImplAgent agent
    ) {
        if (childContext == null || agent == null || agent.getMemory() == null
                || agent.getMemory().getMessages() == null) {
            return List.of();
        }

        Set<String> toolCallIds = new LinkedHashSet<>();
        for (Message message : agent.getMemory().getMessages()) {
            if (message == null || message.getToolCalls() == null) {
                continue;
            }
            for (ToolCall toolCall : message.getToolCalls()) {
                if (toolCall != null && StringUtils.isNotBlank(toolCall.getId())) {
                    toolCallIds.add(toolCall.getId());
                }
            }
        }
        if (toolCallIds.isEmpty()) {
            return List.of();
        }

        Set<ToolArtifactBinding> bindings = new LinkedHashSet<>();
        for (String toolCallId : toolCallIds) {
            for (ToolArtifactBinding binding : childContext.getArtifactBindingsByToolCallId(toolCallId)) {
                if (binding != null && !binding.isInternalFile() && binding.getFile() != null) {
                    bindings.add(binding);
                }
            }
        }
        return new ArrayList<>(bindings);
    }

    private static void emitSubAgentProgress(AgentContext childContext,
                                             String agentId,
                                             String agentType,
                                             String description,
                                             long startMs,
                                             AtomicBoolean finished) {
        if (finished.get() || childContext == null || childContext.getPrinter() == null) {
            return;
        }
        if (childContext.isRunCancelled()) {
            return;
        }
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            // 与前端 agentRuntime 契约对齐：kind=heartbeat|text|line
            payload.put("kind", "heartbeat");
            payload.put("phase", "working");
            payload.put("agentId", agentId);
            payload.put("agentType", agentType);
            payload.put("description", description);
            payload.put("elapsedMs", System.currentTimeMillis() - startMs);
            payload.put("status", "running");
            // parentToolUseId / subAgent* 由 SubAgentPrinter 自动注入
            childContext.getPrinter().send("subagent_progress", payload);
        } catch (Exception e) {
            log.debug("subagent progress heartbeat skipped id={}: {}", agentId, e.getMessage());
        }
    }

    private static String resolveParentToolUseId(AgentContext parent) {
        if (parent == null || parent.getCurrentToolArtifactSource() == null) {
            return null;
        }
        return StringUtils.trimToNull(parent.getCurrentToolArtifactSource().getToolCallId());
    }

    /**
     * 父 run 在子 Agent 启动前已经取消时，不能再启动子 Agent，但必须留下
     * 一条可查询的 STOPPED 子 run，避免调用方只看到一个没有账本的失败结果。
     */
    private void recordCancelledBeforeStart(AgentContext parentContext,
                                            String description,
                                            String prompt,
                                            SubAgentDefinition definition,
                                            String agentId,
                                            String childSessionId,
                                            RunCancellation cancellationOverride) {
        try {
            ToolCollection parentTools = parentContext.getSubAgentToolCollection() != null
                    ? parentContext.getSubAgentToolCollection()
                    : parentContext.getToolCollection();
            ToolCollection childTools = SubAgentToolFilter.filter(parentTools, definition);
            AgentContext childContext = SubAgentContextFactory.create(
                    parentContext, prompt, description, childTools, agentId,
                    definition.getAgentType(), resolveParentToolUseId(parentContext), null, null);
            if (cancellationOverride != null) {
                childContext.control().bindCancellation(cancellationOverride);
            }
            ExecutionLedgerRunSupport.initializeSubAgentRun(
                    childContext.getExecutionRecorder(),
                    childContext,
                    ExecutionLedgerConstants.ENTRY_AGENT_SUBAGENT,
                    prompt
            );
            ExecutionLedgerRunSupport.finishRun(
                    childContext,
                    ExecutionLedgerConstants.STATUS_STOPPED,
                    null,
                    "USER_CANCELLED",
                    "父 run 已停止，取消子 Agent 启动"
            );
        } catch (RuntimeException e) {
            log.warn("{} persist cancelled subagent run failed id={} childSessionId={}",
                    parentContext == null ? null : parentContext.getRequestId(),
                    agentId,
                    childSessionId,
                    e);
        }
    }

    private static int countToolUses(ReactImplAgent agent) {
        if (agent.getMemory() == null || agent.getMemory().getMessages() == null) {
            return 0;
        }
        int count = 0;
        for (Message message : agent.getMemory().getMessages()) {
            if (message != null && message.getRole() == RoleType.TOOL) {
                count++;
            }
        }
        return count;
    }

    private static SubAgentResult failed(String agentId,
                                         String childSessionId,
                                         SubAgentDefinition definition,
                                         String description,
                                         String prompt,
                                         long start,
                                         String errorMsg) {
        return failed(agentId, childSessionId, definition, description, prompt, start, errorMsg, "", null);
    }

    private static SubAgentResult failed(String agentId,
                                         String childSessionId,
                                         SubAgentDefinition definition,
                                         String description,
                                         String prompt,
                                         long start,
                                         String errorMsg,
                                         String content,
                                         Boolean memoryPersisted) {
        return SubAgentResult.builder()
                .status(SubAgentResult.STATUS_FAILED)
                .agentId(agentId)
                .childSessionId(childSessionId)
                .agentType(definition == null ? null : definition.getAgentType())
                .description(description)
                .prompt(prompt)
                .content(StringUtils.defaultString(content))
                .totalToolUseCount(0)
                .totalDurationMs(System.currentTimeMillis() - start)
                .errorMsg(errorMsg)
                .memoryPersisted(memoryPersisted)
                .build();
    }
}
