package org.wwz.ai.domain.agent.runtime.agent;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.memory.ltm.LtmMemoryGuard;
import org.wwz.ai.domain.agent.memory.ltm.LtmPromptGuidance;
import org.wwz.ai.domain.agent.runtime.stream.ToolResultStreamPayload;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactFormatter;
import org.wwz.ai.domain.agent.runtime.budget.RunBudget;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.dto.Memory;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.llm.ContextTokenTracker;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.llm.LlmAskToolProtocol;
import org.wwz.ai.domain.agent.runtime.llm.LlmPromptShapeFactory;
import org.wwz.ai.domain.agent.runtime.llm.LlmToolCallbackProvider;
import org.wwz.ai.domain.agent.runtime.llm.PromptShape;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.domain.agent.runtime.llm.SessionPromptFreeze;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModePromptInjector;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationSerializer;
import org.wwz.ai.domain.agent.runtime.tool.common.MemoryTool;
import org.wwz.ai.domain.agent.runtime.tool.common.SessionSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.skill.SkillViewTool;
import org.wwz.ai.domain.agent.runtime.tasklist.BackgroundTaskNotificationFormatter;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;
import org.wwz.ai.domain.agent.runtime.tool.deferred.DeferredToolCall;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillPromptIndexBuilder;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceReadStateStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 所有 Agent 的抽象基类。
 * 固定执行主循环，并统一承接记忆管理、工具执行和执行账本接入。
 * <p>
 * 子类只需要实现 {@link #step()}，本类负责保证每一步都经过取消检查、运行位置刷新、
 * 工作记忆压缩和最大步数保护；工具执行则统一经过 observation、账本和产物收口。
 */
@Slf4j
@Data
@Accessors(chain = true)
public abstract class BaseAgent {

    /** 不单独向前端发送工具结果的工具；它们通过产物或结构化事件展示结果。 */
    private static final Set<String> TOOLS_WITHOUT_RESULT_EVENT = Set.of(
            "code_interpreter", "document_generate", "slides_generate",
            "excel_generator", "checklist_generate", "template_filler", "document_template",
            "theme_designer", "chart_generator", "deep_search",
            "multimodalagent_tool", "data_analysis", "canvas_publish");

    /** 只有最终文件交付工具需要把 artifactKey 暴露给模型完成精确文件选择。 */
    private static final Set<String> TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY = Set.of(
            "deep_search",
            "document_generate",
            "slides_generate",
            "excel_generator",
            "checklist_generate",
            "template_filler",
            "document_template",
            "theme_designer",
            "chart_generator");

    /** Agent 名称 */
    private String name;
    /** Agent 描述 */
    private String description;
    /** 系统提示词 */
    private String systemPrompt;
    /** 下一步提示词 */
    private String nextStepPrompt;
    /** 当前 Agent 可用工具集合 */
    public ToolCollection availableTools = new ToolCollection();
    /** Agent 记忆 */
    private Memory memory = new Memory();
    /** LLM 门面 */
    protected LLM llm;
    /** Agent 上下文 */
    protected AgentContext context;

    /** 当前状态 */
    private AgentState state = AgentState.IDLE;
    /** 最大步数 */
    private int maxSteps = 10;
    /** 当前步号 */
    private int currentStep = 0;
    /** 重复阈值，暂未启用 */
    private int duplicateThreshold = 2;

    /** 输出器 */
    Printer printer;

    /** 数字员工提示词 */
    private String digitalEmployeePrompt;

    private final transient ToolExecutionPipeline toolPipeline = new ToolExecutionPipeline(this);

    /** 本 run 是否已做过一次「notified=0 终态任务」补投扫描。 */
    private transient boolean backgroundNotificationBackfillDone = false;

    /**
     * 子类定义单步执行逻辑。
     */
    public abstract String step();

    /** 预算进入收尾阶段时由子类生成最终答案；默认直接结束本轮。 */
    protected String finalizeAfterBudget(String reason) {
        setState(AgentState.FINISHED);
        return null;
    }

    protected <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    protected void ensureQueryMessage() {
        if (getMemory().getLastMessage() == null) {
            String seed = context.getQuery() == null ? "" : context.getQuery();
            getMemory().addMessage(Message.userMessage(seed, null));
        }
    }

    protected void appendAssistantMessage(LLM.ToolCallResponse response) {
        Message assistantMessage = response.getToolCalls() != null
                && !response.getToolCalls().isEmpty()
                && LlmAskToolProtocol.fromCode(llm.getFunctionCallType()) != LlmAskToolProtocol.STRUCT_PARSE
                ? Message.fromToolCalls(response.getContent(), response.getReasoningContent(), response.getToolCalls())
                : Message.assistantMessage(response.getContent(), response.getReasoningContent(), null);
        getMemory().addMessage(assistantMessage);
        recordMainAskToolUsage(response);
    }

    /**
     * 仅主 Agent 正式 askTool 响应更新 tracker。摘要 / flush / LTM / 内部 LLM 不走这里。
     */
    private void recordMainAskToolUsage(LLM.ToolCallResponse response) {
        if (context == null || response == null || getMemory() == null) {
            return;
        }
        PromptShape shape = currentAskToolPromptShape(getMemory().getMessages());
        context.contextTokenTracker().recordProviderUsage(
                response.toUsageSnapshot(),
                getMemory().getMessages().size(),
                shape == null ? null : new TokenCounter().fingerprint(shape));
    }

    private PromptShape currentAskToolPromptShape(List<Message> messages) {
        LlmAskToolProtocol protocol = llm == null
                ? LlmAskToolProtocol.FUNCTION_CALL
                : LlmPromptShapeFactory.protocolOf(llm.getFunctionCallType());
        Message system = StringUtils.isBlank(getSystemPrompt())
                ? null
                : Message.systemMessage(getSystemPrompt(), null);
        return LlmPromptShapeFactory.forAskTool(system, messages, availableTools, protocol);
    }

    protected Map<String, Object> parseToolParam(ToolCall command) {
        return toolPipeline.parseToolParam(command);
    }

    protected void sendToolResult(ToolCall command, String toolResult) {
        sendToolResult(command, toolResult, null);
    }

    protected void sendToolResult(ToolCall command, ToolExecutionOutcome outcome) {
        sendToolResult(command,
                outcome == null ? null : outcome.getToolResult(),
                outcome == null ? null : outcome.getTiming());
    }

    private void sendToolResult(ToolCall command, String toolResult,
                                org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming timing) {
        if (printer == null || command == null || command.getFunction() == null) {
            return;
        }
        String toolName = command.getFunction().getName();
        Map<String, Object> toolParam = parseToolParam(command);
        if (org.wwz.ai.domain.agent.runtime.tool.common.mcp.McpToolNames.TOOL_CALL.equals(toolName)) {
            DeferredToolCall.Result resolved = DeferredToolCall.resolve(availableTools, toolParam);
            if (resolved.ok()) {
                toolName = resolved.name();
                toolParam = resolved.arguments();
            }
        }
        if (TOOLS_WITHOUT_RESULT_EVENT.contains(toolName)) {
            return;
        }
        ToolResultStreamPayload result = ToolResultStreamPayload.builder()
                .toolName(toolName)
                .toolParam(toolParam)
                .toolResult(toolResult)
                .toolCallId(command.getId())
                .build();
        if (timing == null) {
            printer.send(command.getId(), "tool_result", result, null, true);
            return;
        }
        Map<String, Object> extraResultMap = new LinkedHashMap<>();
        extraResultMap.put("timing", timing);
        printer.send(command.getId(), "tool_result", result, extraResultMap, null, true);
    }

    /**
     * Agent 主循环。
     */
    public String run(String query) {
        setState(AgentState.IDLE);

        // 多轮 cache 关键：有 working memory 时只 preload+append query，禁止重插 session_env 打乱前缀
        // 首轮：seed env → query；结束时把 env+本轮轨迹一并 persist
                boolean hasWorking = context != null
                && context.getWorkingMemoryMessages() != null
                && !context.getWorkingMemoryMessages().isEmpty();

        if (hasWorking) {
            // 跨轮：用历史整段替换 memory，再 append 本轮 query（严格前缀续写）
            memory.replaceMessages(new ArrayList<>(context.getWorkingMemoryMessages()));
        } else {
            // 首轮：session_env + query
            if (memory != null) {
                memory.clear();
            }
            seedSessionContextMessages();
        }
        if (query != null && !query.isEmpty()) {
            String userContent = query;
            // 深度记忆 prefetch 挂在 user 侧围栏；skip_memory 不注入
            if (context != null
                    && !Boolean.TRUE.equals(context.getSkipMemory())
                    && StringUtils.isNotBlank(context.getLtmMemoryContext())) {
                userContent = context.getLtmMemoryContext().trim() + "\n\n" + userContent;
            }
            updateMemory(RoleType.USER, userContent, null);
        }

        List<String> results = new ArrayList<>();
        try {
            // 每次循环只推进一个逻辑步骤；step() 可以触发多次 LLM/tool 调用，但不能绕过这里的边界检查。
            while (currentStep < maxSteps && !state.isTerminal()) {
                if (context != null && context.isRunCancelled()) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: User stopped");
                    log.info("{} {} run cancelled reason={}",
                            context.getRequestId(), getName(), context.getRunCancelReason());
                    break;
                }
                RunBudget runBudget = context == null ? null : context.getRunBudget();
                String finalizationReason = runBudget == null
                        ? null
                        : runBudget.claimFinalization(this, currentStep);
                if (finalizationReason != null) {
                    String finalText = finalizeAfterBudget(finalizationReason);
                    boolean finalized = StringUtils.isNotBlank(finalText)
                            && state == AgentState.FINISHED;
                    runBudget.completeFinalization(this, finalized);
                    if (finalized) {
                        results.add(finalText);
                    } else if (state != AgentState.FINISHED) {
                        // 只有真正尝试收尾且没有进入终态时，才追加失败结果。
                        results.add("Terminated: Budget finalization failed (" + finalizationReason + ")");
                    }
                    log.info("{} {} budget finalization reason={} success={} tokens={} tools={}",
                            context.getRequestId(), getName(), finalizationReason, finalized,
                            runBudget.getTotalTokens(), runBudget.getToolCalls());
                    break;
                }
                currentStep++;
                if (context != null) {
                    // 步进边界 drain 控制面 inject（用户中途指导），再进入 think
                    drainPendingInjectsIntoMemory();
                    // 后台子 Agent 完成通知：同一轮 steer，或唤醒轮第一步补投
                    drainBackgroundTaskNotificationsIntoMemory();
                    // 每步进入前都刷新一次当前位置，供 LLM / tool 账本读取。
                    context.markExecutionPosition(getName(), currentStep);
                    // Plan Mode：sparse/full 提醒 + mid-run Enter 时补 system 指引
                    PlanModePromptInjector.injectStepReminders(this);
                    //每步 LLM 前再判一次上下文水位（含 tool 后中途）
                    compactWorkingMemoryIfNeeded("step");
                }
                log.info("{} {} Executing step {}/{}", context.getRequestId(), getName(), currentStep, maxSteps);
                results.add(step());
            }

            if (currentStep >= maxSteps && !state.isTerminal()) {
                // 达到步数上限时以可识别的终止结果结束本轮，避免调用方误以为仍可继续执行。
                currentStep = 0;
                state = AgentState.IDLE;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
        } catch (Exception e) {
            // 状态先切换为 ERROR，再把异常交给上层处理；账本最终状态由应用编排层统一写回。
            state = AgentState.ERROR;
            throw e;
        }

        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }

    /**
     * 步进边界：把控制面 inject 写入 Memory，供下一轮 think 看到。
     */
    protected void drainPendingInjectsIntoMemory() {
        if (context == null) {
            return;
        }
        SessionInboxPort inbox = context.sessionInboxPort();
        if (inbox != null) {
            List<SessionInboxItem> items = claimInboxSafely(
                    inbox, SessionInboxItem.KIND_USER_MESSAGE);
            List<Long> consumedIds = new ArrayList<>();
            for (SessionInboxItem item : items) {
                String text = item == null ? null : SessionInboxPayloads.userText(item.getPayloadJson());
                if (StringUtils.isBlank(text)) {
                    log.warn("{} {} ignored empty inbox user message id={}",
                            context.getRequestId(), getName(), item == null ? null : item.getId());
                    if (item != null && item.getId() != null) {
                        consumedIds.add(item.getId());
                    }
                    continue;
                }
                String wrapped = "<system-reminder>\n"
                        + "The user sent a mid-run guidance message. "
                        + "Follow it carefully for the rest of this run.\n"
                        + "</system-reminder>\n\n"
                        + text.trim();
                updateMemory(RoleType.USER, wrapped, null);
                consumedIds.add(item.getId());
                log.info("{} {} drained inbox user message id={} chars={}",
                        context.getRequestId(), getName(), item.getId(), text.length());
            }
            context.control().addConsumedInboxItemIds(consumedIds);
        }
        // Inbox 只承载主会话用户输入；子 Agent 的 SendMessage 邮箱仍在内存队列，必须继续 drain。
        List<PendingInjectMessage> injects = context.drainPendingInjects();
        if (injects == null || injects.isEmpty()) {
            return;
        }
        for (PendingInjectMessage inject : injects) {
            if (inject == null || StringUtils.isBlank(inject.getText())) {
                continue;
            }
            String source = StringUtils.defaultIfBlank(inject.getSource(), PendingInjectMessage.SOURCE_USER);
            String who = PendingInjectMessage.SOURCE_COORDINATOR.equals(source)
                    ? "The main/coordinator agent"
                    : "The user";
            String wrapped = "<system-reminder>\n"
                    + who + " sent a mid-run guidance message (source=" + source + "). "
                    + "Follow it carefully for the rest of this run.\n"
                    + "</system-reminder>\n\n"
                    + inject.getText().trim();
            updateMemory(RoleType.USER, wrapped, null);
            log.info("{} {} drained inject source={} chars={}",
                    context.getRequestId(), getName(), source, inject.getText().length());
        }
    }

    /**
     * 步进边界：把后台任务完成通知写入 Memory（仅模型可见）。
     * <p>首次 drain 时同时补投 {@code notified=0} 的终态任务，覆盖进程重启/崩溃窗口；
     * 已由队列投递的任务按 taskId 去重。成功收口后才由收口点统一 markNotified。</p>
     */
    protected void drainBackgroundTaskNotificationsIntoMemory() {
        if (context == null || memory == null) {
            return;
        }
        if (context.getRuntimeDependencies() != null
                && context.getRuntimeDependencies().getCuteConfig() != null
                && !context.getRuntimeDependencies().getCuteConfig().isBackgroundAutoContinuation()) {
            return;
        }
        SessionInboxPort inbox = context.sessionInboxPort();
        String sessionKey = SessionBackgroundTaskHub.keyFor(
                context.getSessionId(), context.getRequestId());
        List<TaskNotification> batch = new ArrayList<>(SessionNotificationHub.drain(sessionKey));
        Set<String> batchTaskIds = new LinkedHashSet<>();
        List<Long> consumedIds = new ArrayList<>();
        for (TaskNotification notification : batch) {
            if (notification != null && StringUtils.isNotBlank(notification.taskId())) {
                batchTaskIds.add(notification.taskId());
            }
        }
        if (inbox != null) {
            List<SessionInboxItem> items = claimInboxSafely(
                    inbox, SessionInboxItem.KIND_BACKGROUND_NOTIFICATION);
            for (SessionInboxItem item : items) {
                TaskNotification notification = item == null
                        ? null : SessionInboxPayloads.backgroundNotification(item.getPayloadJson());
                if (notification == null || StringUtils.isBlank(notification.taskId())) {
                    log.warn("{} {} ignored malformed inbox background notification id={}",
                            context.getRequestId(), getName(), item == null ? null : item.getId());
                    if (item != null && item.getId() != null) {
                        consumedIds.add(item.getId());
                    }
                    continue;
                }
                if (batchTaskIds.add(notification.taskId())) {
                    batch.add(notification);
                }
                consumedIds.add(item.getId());
            }
        }
        context.control().addConsumedInboxItemIds(consumedIds);
        if (!context.isSubAgent() && !backgroundNotificationBackfillDone) {
            // run 级只扫一次：之后新完成的任务都会走完成路径入队。
            // 子 Agent 与父会话共享 registry，但补投只归主会话：否则父会话未投递的通知
            // 会被塞进子 Agent 的上下文。
            backgroundNotificationBackfillDone = true;
            RuntimeBackgroundTaskRegistry registry = context.requireBackgroundTasks();
            for (RuntimeBackgroundTask task : registry.listAll()) {
                if (task == null || StringUtils.isBlank(task.getId())) {
                    continue;
                }
                if (RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus())
                        || RuntimeBackgroundTask.STATUS_STOPPED.equals(task.getStatus())
                        || Boolean.TRUE.equals(task.getNotified())
                        || batchTaskIds.contains(task.getId())) {
                    continue;
                }
                // 补投不看内存 claim：上一轮可能已 drain 但没成功落库，本轮必须重新注入。
                // 同一轮内由 batchTaskIds 去重；会话单 run 仲裁保证不会并发补投。
                TaskNotification notification = TaskNotification.from(task, null);
                if (notification != null) {
                    if (batchTaskIds.add(task.getId())) {
                        batch.add(notification);
                    }
                }
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        String formatted = BackgroundTaskNotificationFormatter.formatBatch(batch);
        if (StringUtils.isBlank(formatted)) {
            return;
        }
        String wrapped = "<system-reminder>\n"
                + "以下内容是后台任务完成通知（不是用户输入，也不代表用户提出了新问题）。"
                + "请结合这些结果继续当前任务；如果已经给用户最终答复，请在新的回复中简要汇报相关结果。\n"
                + "</system-reminder>\n\n"
                + formatted;
        memory.addMessage(Message.modelOnlyUserMessage(wrapped));
        context.control().addConsumedBackgroundNotifications(batchTaskIds);
        log.info("{} {} drained background notifications count={} taskIds={}",
                context.getRequestId(), getName(), batch.size(), batchTaskIds);
    }

    private List<SessionInboxItem> claimInboxSafely(SessionInboxPort inbox, String kind) {
        try {
            List<SessionInboxItem> items = inbox.claimPending(
                    context.getSessionId(),
                    context.getRequestId(),
                    kind,
                    100);
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            List<SessionInboxItem> fresh = new ArrayList<>(items.size());
            for (SessionInboxItem item : items) {
                if (item == null) {
                    continue;
                }
                Long itemId = item.getId();
                if (itemId == null || context.control().markInboxItemClaimed(itemId)) {
                    fresh.add(item);
                } else {
                    log.warn("{} {} skipped duplicate inbox claim id={} kind={}",
                            context.getRequestId(), getName(), itemId, kind);
                }
            }
            return fresh;
        } catch (Exception e) {
            log.warn("{} {} session inbox claim failed kind={}",
                    context.getRequestId(), getName(), kind, e);
            return List.of();
        }
    }

    /**
     * 追加记忆消息。
     */
    public void updateMemory(RoleType role, String content, String base64Image, Object... args) {
        Message message;
        switch (role) {
            case USER:
                message = Message.userMessage(content, base64Image);
                break;
            case SYSTEM:
                message = Message.systemMessage(content, base64Image);
                break;
            case ASSISTANT:
                message = Message.assistantMessage(content, base64Image);
                break;
            case TOOL:
                message = Message.toolMessage(content, (String) args[0], base64Image);
                break;
            default:
                throw new IllegalArgumentException("Unsupported role type: " + role);
        }
        memory.addMessage(message);
    }

    /**
     * 预装历史消息，避免共享同一份可变列表。
     */
    protected void preloadMemory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        memory.addMessages(new ArrayList<>(messages));
    }


    /**
     * 组装 cache-friendly system：只保留静态/会话级占位，query/date/files/history 不进 system。
     * <p>
     * 纯函数规范化：同输入必得同字节，不依赖 SessionPromptFreeze 内存。
     * tools 只走 API tools[]（忽略 toolPrompt 正文），避免 system 与 schema 双轨。
     */
    protected String buildStableSystemPrompt(String template, String extraPlaceholder, String extraValue) {
        String systemTemplate = normalizeSystemSource(template);
        String basePrompt = context == null || context.getBasePrompt() == null
                ? ""
                : context.getBasePrompt().trim();
        // tools 只走 API tools[]，不再写入 system 正文
        systemTemplate = systemTemplate
                .replace("{{tools}}", "")
                .replace("{{basePrompt}}", basePrompt);
        if (extraPlaceholder != null && !extraPlaceholder.isEmpty()) {
            systemTemplate = systemTemplate.replace(extraPlaceholder, extraValue == null ? "" : extraValue);
        }
        // Tool guidance (WHEN/SKIP) + curated snapshot; skip_memory 不注入
        if (context != null && !Boolean.TRUE.equals(context.getSkipMemory())) {
            var tools = context.getToolCollection();
            boolean memoryToolPresent = tools != null && tools.getTool(MemoryTool.TOOL_NAME) != null;
            boolean sessionSearchPresent = tools != null && tools.getTool(SessionSearchTool.TOOL_NAME) != null;
            String ltmGuidance = LtmPromptGuidance.forLoadedTools(memoryToolPresent, sessionSearchPresent);
            if (StringUtils.isNotBlank(ltmGuidance) && !systemTemplate.contains(LtmPromptGuidance.MEMORY_GUIDANCE)
                    && !systemTemplate.contains(ltmGuidance)) {
                systemTemplate = systemTemplate.trim() + "\n\n" + ltmGuidance.trim() + "\n";
            }
            if (context.getRuntimeDependencies() != null) {
                var ltmManager = context.getRuntimeDependencies().getOptionalLtmManager();
                if (ltmManager != null) {
                    String ltmBlock = ltmManager.buildSystemPrompt();
                    if (StringUtils.isNotBlank(ltmBlock) && !systemTemplate.contains(ltmBlock)) {
                        systemTemplate = systemTemplate.trim() + "\n\n" + ltmBlock.trim() + "\n";
                    }
                }
            }
        }
        systemTemplate = appendSkillPromptIndex(systemTemplate);
        systemTemplate = canonicalizeSystemText(systemTemplate);
        // Freeze 仅作同 session 防御缓存；主稳定性来自确定性规范化
        String toolSig = LlmToolCallbackProvider.buildToolSignature(
                context == null ? null : context.getToolCollection());
        String agentSlot = StringUtils.defaultIfBlank(getName(), "agent");
        String sessionId = context == null ? null : context.getSessionId();
        return SessionPromptFreeze.freezeSystem(
                sessionId, agentSlot, toolSig, systemTemplate);
    }

    /**
     * 兼容旧的单参数调用方，统一复用当前稳定 system prompt 规范化逻辑。
     */
    protected String buildStableSystemPrompt(String template) {
        return buildStableSystemPrompt(template, null, null);
    }

    private String appendSkillPromptIndex(String systemTemplate) {
        if (context == null || context.getToolCollection() == null) {
            return systemTemplate;
        }
        BaseTool viewTool = context.getToolCollection().getTool(SkillViewTool.NAME);
        if (!(viewTool instanceof SkillViewTool skillViewTool)) {
            return systemTemplate;
        }
        String index = skillViewTool.promptIndex(context);
        if (StringUtils.isBlank(index) || systemTemplate.contains(SkillPromptIndexBuilder.AVAILABLE_SKILLS_OPEN)) {
            return systemTemplate;
        }
        return systemTemplate.trim() + "\n\n" + index.trim() + "\n";
    }

    /**
     * 入参归一：统一换行，去掉 BOM，避免多次构造时源串细微差异。
     */
    protected String normalizeSystemSource(String system) {
        if (system == null || system.isEmpty()) {
            return "";
        }
        String s = system;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 输出字节规范化：行尾空白、连续空行、统一结尾换行 —— 同逻辑内容必同字节。
     */
    protected String canonicalizeSystemText(String system) {
        if (system == null || system.isEmpty()) {
            return "";
        }
        String[] parts = system.split("\n", -1);
        StringBuilder sb = new StringBuilder(system.length());
        int blankRun = 0;
        for (String line : parts) {
            String trimmedRight = rtrimSpaces(line);
            if (trimmedRight.isEmpty()) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
                sb.append('\n');
            } else {
                blankRun = 0;
                sb.append(trimmedRight).append('\n');
            }
        }
        String out = trimNewlines(sb.toString());
        return out.isEmpty() ? "" : out + "\n";
    }

    private static String rtrimSpaces(String line) {
        int endIdx = line.length();
        while (endIdx > 0) {
            char c = line.charAt(endIdx - 1);
            if (c != ' ' && c != '\t') {
                break;
            }
            endIdx--;
        }
        return line.substring(0, endIdx);
    }

    private static String trimNewlines(String s) {
        int startIdx = 0;
        int endIdx = s.length();
        while (startIdx < endIdx && s.charAt(startIdx) == '\n') {
            startIdx++;
        }
        while (endIdx > startIdx && s.charAt(endIdx - 1) == '\n') {
            endIdx--;
        }
        return s.substring(startIdx, endIdx);
    }



    /**
     * 会话级 env（date）预置到 memory 前缀；history 走 workingMemoryMessages preload
     */
    protected void seedSessionContextMessages() {
        if (context == null || memory == null) {
            return;
        }
        if (!memory.getMessages().isEmpty()) {
            Message first = memory.getMessages().get(0);
            if (first != null && first.getContent() != null
                    && first.getContent().contains("<session_env>")) {
                return;
            }
        }
        String dateInfo = context.getDateInfo() == null ? "" : context.getDateInfo();
        if (!dateInfo.isBlank()) {
            memory.addMessage(Message.userMessage(
                    "<session_env>\n当前日期：" + dateInfo + "\n</session_env>",
                    null));
        }
    }





    protected void initializePromptsWithHistoryOnlyInSystem(Map<String, String> systemPromptMap,
                                                            Map<String, String> nextStepPromptMap,
                                                            String defaultSystemPrompt,
                                                            String defaultNextStepPrompt,
                                                            String extraPlaceholder,
                                                            String extraValue) {
        Map<String, String> map = systemPromptMap == null ? Map.of() : systemPromptMap;
        String template = AgentPrompt.ensureUserFacingReplyContract(
                map.getOrDefault("default", defaultSystemPrompt));
        setSystemPrompt(buildStableSystemPrompt(template, extraPlaceholder, extraValue));
        setNextStepPrompt(null);
    }

    protected void initializeSystemPrompt(Map<String, String> systemPromptMap, String defaultSystemPrompt) {
        initializePromptsWithHistoryOnlyInSystem(
                systemPromptMap,
                null,
                defaultSystemPrompt,
                null,
                null,
                null);
    }

    /**
     * 子 Agent：REACT 底座 + 面向协调者终答 + 类型指令，整段替换（去掉 USER_FACING）。
     */
    public void applySubAgentSystemPrompt(String typeDirective) {
        String template = AgentPrompt.composeSubAgentSystemPrompt(typeDirective);
        setSystemPrompt(buildStableSystemPrompt(template, null, null));
    }

    /**
     * PlanSolve 主：ORCHESTRATION 底座 + USER_FACING，整段替换（不用 REACT 底座）。
     */
    public void applyPlanSolveSystemPrompt() {
        String template = AgentPrompt.composePlanSolveSystemPrompt();
        setSystemPrompt(buildStableSystemPrompt(template, null, null));
    }

    /**
     * 在当前 system prompt 尾部追加 openui 输出通道（语法协议 + 核心组件签名）。
     *
     * <p>扩展组件的定义不进提示词，留在 {@code get_ui_components} 工具里按需取，
     * 所以这里注入的是固定约 2.5k tokens 的协议本体，不是全量组件库。</p>
     *
     * <p>幂等：{@link AgentPrompt#ensureOpenUiProtocol} 靠 OPENUI_PROTOCOL_V1 marker 判断是否已注入。</p>
     */
    public void applyOpenUiProtocol() {
        setSystemPrompt(AgentPrompt.ensureOpenUiProtocol(getSystemPrompt()));
    }

    /**
     * 为单次工具结果追加当前 toolCall 的文件摘要。
     */
    protected String attachToolArtifactSummary(String result, String toolCallId) {
        if (context == null || StringUtils.isBlank(toolCallId)) {
            return result;
        }
        var bindings = context.getArtifactBindingsByToolCallId(toolCallId).stream()
                .filter(binding -> binding != null && !binding.isInternalFile())
                .filter(binding -> binding.getSource() != null
                        && TOOLS_REQUIRING_LLM_ARTIFACT_SUMMARY.contains(binding.getSource().getToolName()))
                .toList();
        return ToolArtifactFormatter.appendToolArtifactSummary(
                result,
                bindings
        );
    }

    /**
     * 子类按需覆写 observation 最大长度。
     * BaseAgent 本身不关心具体配置来源，只负责统一收口规则。
     */
    protected Integer resolveMaxObserveLength() {
        return null;
    }

    /**
     * 统一生成最终 observation。
     * 先做长度裁剪，再追加当前 toolCall 关联的产物摘要，
     * 确保账本与主智能体看到的内容完全一致；artifact 摘要不参与 maxObserve 裁剪。
     */
    protected String buildFinalLlmObservation(String rawObservation, String toolCallId) {
        String observation = StringUtils.defaultString(rawObservation);
        Integer maxObserve = resolveMaxObserveLength();
        if (maxObserve != null && maxObserve > 0) {
            observation = ToolObservationSerializer.truncateForLlm(observation, maxObserve);
        }
        return attachToolArtifactSummary(observation, toolCallId);
    }

    /**
     * 把工具最终 observation 写回记忆。
     * 无论单工具还是批量工具，都统一走这一条链路，避免不同 Agent 各自拼装结果。
     */
    protected String writeToolObservationToMemory(ToolCall command, ToolExecutionOutcome outcome) {
        String observation = outcome == null ? "" : StringUtils.defaultString(outcome.getLlmObservation());
        if (command == null) {
            return observation;
        }
        if (LlmAskToolProtocol.fromCode(llm.getFunctionCallType()) == LlmAskToolProtocol.STRUCT_PARSE) {
            // struct_parse 没有 tool_use_id 绑定，observation 直接拼进 assistant content，
            // 投影后 JSON 里的 tool 字段已不存在，必须补回工具名，否则模型不知道结果归属。
            String toolName = command.getFunction() == null ? "tool" : command.getFunction().getName();
            String content = getMemory().getLastMessage().getContent();
            getMemory().getLastMessage().setContent(
                    content + "\n 工具执行结果为:\n[" + toolName + "] " + observation);
            return observation;
        }
        getMemory().addMessage(Message.toolMessage(observation, command.getId(), outcome.getBase64Image()));
        return observation;
    }

    public String executeTool(ToolCall command) {
        return executeToolOutcome(command).getLlmObservation();
    }

    protected ToolExecutionOutcome executeToolOutcome(ToolCall command) {
        return toolPipeline.executeOne(command);
    }

    public Map<String, String> executeTools(List<ToolCall> commands) {
        Map<String, ToolExecutionOutcome> outcomes = executeToolOutcomes(commands);
        Map<String, String> result = new LinkedHashMap<>(outcomes.size());
        for (Map.Entry<String, ToolExecutionOutcome> entry : outcomes.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().getLlmObservation());
        }
        return result;
    }

    protected Map<String, ToolExecutionOutcome> executeToolOutcomes(List<ToolCall> commands) {
        return toolPipeline.executeBatch(commands);
    }

    protected Map<String, Long> ensureToolInvocationIds(List<ToolCall> commands) {
        return toolPipeline.ensureToolInvocationIds(commands);
    }

    protected Map<String, Long> preRegisterToolInvocations(List<ToolCall> commands) {
        return toolPipeline.preRegisterToolInvocations(commands);
    }

    public List<Message> exportWorkingMemoryDelta() {
        List<Message> all = memory == null ? List.of() : memory.getMessages();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        // mid-run compact 后 workingMemoryMessages 已同步为新前缀：只 persist 后缀 delta
        int preloadSize = 0;
        if (context != null && context.getWorkingMemoryMessages() != null) {
            preloadSize = context.getWorkingMemoryMessages().size();
        }
        if (preloadSize <= 0) {
            return new ArrayList<>(all);
        }
        if (preloadSize >= all.size()) {
            return List.of();
        }
        // 持久化只写本轮新增后缀，避免把跨轮前缀重复写入 working_memory 投影表。
        return new ArrayList<>(all.subList(preloadSize, all.size()));
    }

    /**
     * 每次即将调用主模型前，对当前 Memory 做阈值压缩。
     * 成功后同步 memory + context.workingMemoryMessages，保证 export delta 正确。
     */
    protected void compactWorkingMemoryIfNeeded(String phase) {
        if (context == null || memory == null) {
            return;
        }
        // LTM flush/review fork：禁止 mid-run compact，否则会递归 flush 并冲掉对话快照
        if (LtmMemoryGuard.isSideEffectsDisabled(context)) {
            return;
        }
        if (context.getRuntimeDependencies() == null) {
            return;
        }
        var compaction = context.getRuntimeDependencies().getOptionalSessionContextCompactionService();
        if (compaction == null) {
            return;
        }
        List<Message> current = memory.getMessages();
        if (current == null || current.isEmpty()) {
            return;
        }
        try {
            var flushParity = org.wwz.ai.domain.agent.memory.SessionContextCompactionService.LtmFlushParity.of(
                    getSystemPrompt(),
                    availableTools,
                    context.getLtmOwner());
            PromptShape promptShape = currentAskToolPromptShape(current);
            ContextTokenTracker.Snapshot tokenSnapshot = context.contextTokenTracker().snapshot();
            List<Message> compacted = compaction.applyIfNeededMidRun(
                    context.getSessionId(),
                    context.resolveMemoryScope(),
                    context.getRequestId(),
                    current,
                    flushParity,
                    promptShape,
                    tokenSnapshot);
            if (compacted == null || compacted == current || compacted.equals(current)) {
                return;
            }
            // 压缩后的列表同时替换 Agent Memory 和上下文快照，否则后续导出 delta 会从旧前缀计算。
            memory.replaceMessages(new ArrayList<>(compacted));
            context.setWorkingMemoryMessages(new ArrayList<>(compacted));
            context.contextTokenTracker().reset();
            // cc-haha 对齐：压缩后清 read-state，避免正文已不在上下文仍返回 unchanged stub。
            clearWorkspaceReadStateAfterCompaction();
            log.info("{} {} mid-run compact phase={} beforeMsgs={} afterMsgs={}",
                    context.getRequestId(), getName(), phase, current.size(), compacted.size());
        } catch (Exception e) {
            log.warn("{} {} mid-run compact failed phase={}: {}",
                    context.getRequestId(), getName(), phase, e.getMessage());
        }
    }

    private void clearWorkspaceReadStateAfterCompaction() {
        if (context == null) {
            return;
        }
        try {
            new WorkspaceReadStateStore().clear(context);
        } catch (Exception e) {
            context.clearWorkspaceReadState();
            log.warn("{} clear workspace read-state after compact failed: {}",
                    context.getRequestId(), e.getMessage());
        }
    }


}
