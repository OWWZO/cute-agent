package org.wwz.ai.domain.agent.runtime.agent;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactBinding;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactRegistry;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.budget.RunBudget;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.cancel.RunControl;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeState;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.access.ToolAccessPolicy;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceFileReadState;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.llm.ContextTokenTracker;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.memory.WorkingMemoryScopes;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Agent 运行时上下文：请求身份、工具会话、记忆、账本与取消控制。
 * 字段按「构造时确定 → run 内演化」分七区顺序排列；分区只描述归属，不改变扁平访问方式。
 *
 * <p><b>不生成全量 setter。</b>本类曾用 {@code @Data}，导致所有字段全部可写，
 * 任何持有 context 的代码都能改写 {@code requestId} / {@code query} / {@code basePrompt}
 * 这类构造期就该冻结的字段。现改为 {@link Getter} + 白名单 setter：
 * 只有 run 内确实需要演化的字段才提供写入通道，其余字段构造后不可变。</p>
 */
@Getter
@Builder
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    // ---- 1. 请求身份（构造时确定，run 内不变）----
    /**
     * 请求唯一标识（全链路追踪ID）
     * 用途：
     * 1. 日志排查：所有环节的日志均携带该ID，可快速定位单次请求的完整执行链路；
     * 2. 问题定位：关联智能体思考、工具调用、LLM请求等所有环节的异常日志；
     * 格式示例：uuid/雪花ID（如"8f2e9c45-6789-4321-abcd-1234567890ab"）
     */
    String requestId;

    /**
     * 会话ID（用户连续对话标识）
     * 用途：
     * 1. 多轮对话：关联同一用户的多次请求，维护会话级上下文；
     * 2. 记忆管理：基于该ID加载/存储用户的对话历史记忆；
     * 格式示例：用户ID+时间戳（如"user_123456_1710000000000"）
     */
    String sessionId;

    /**
     * 当前用户 ID。浏览器遥控按 userId 索引本机扩展连接，不是 sessionId。
     */
    String userId;

    /**
     * 用户原始查询语句
     * 用途：智能体的核心输入，是所有任务拆解、工具调用的源头；
     * 示例："帮我分析这款产品的市场竞争力并生成报告"
     */
    String query;

    /**
     * 当前智能体执行的具体任务（结构化的query）
     * 用途：
     * 1. 任务拆解：由原始query解析而来的结构化任务（粒度更细）；
     * 2. 工具绑定：关联当前任务专属的工具、文件、提示词；
     * 示例："生成产品市场竞争力分析报告"（对应query的子任务）
     */
    String task;

    // ---- 2. 本轮模型与能力开关（构造时确定）----
    /**
     * 本轮模型引用（modelId 或上游 modelName）；空则 AgentBootstrap 从 MySQL 选择默认模型。
     */
    String model;

    /** 本轮是否开深度思考；null=沿用模型配置 */
    Boolean thinking;

    /** 思考档位 low|medium|high */
    String thinkingEffort;

    /**
     * 智能体类型标识（AgentType 数值：PLAN_SOLVE / REACT 等）
     */
    Integer agentType;

    /**
     * 本会话禁用的 skill 名（差集）；空=全开。
     */
    @Builder.Default
    java.util.Set<String> disabledSkillNames = java.util.Set.of();

    /**
     * 本会话禁用的 mcpId（差集）；空=全开。
     */
    @Builder.Default
    java.util.Set<String> disabledMcpIds = java.util.Set.of();

    /**
     * 非空时：tools[] 仍可上报全量 schema（prompt cache），但 execute 仅允许白名单内工具。
     * LTM review/flush fork 「schema 全量 + runtime whitelist」。
     */
    java.util.Set<String> toolDispatchWhitelist;

    // ---- 3. 输出、运行时依赖与工具（构造时注入，run 内演化）----
    /**
     * 输出器（结果推送工具）
     * 用途：
     * 1. 实时推送：向前端/用户推送智能体执行过程（如思考过程、计划步骤、工具执行结果）；
     * 2. 消息分类：支持按类型（plan_thought/task/plan）推送不同格式的消息；
     * 核心方法：printer.send(String type, Object content)
     */
    @JSONField(serialize = false)
    Printer printer;

    /**
     * 工具集合（智能体可调用的所有工具容器）
     * 用途：
     * 1. 工具管理：存储所有可用工具（基础工具、数字员工工具）的元信息、状态；
     * 2. 工具调用：智能体思考阶段从该集合中选择待执行的工具；
     * 核心能力：工具注册、更新、查询、执行结果存储
     */
    @JSONField(serialize = false)
    ToolCollection toolCollection;

    /**
     * 主 Agent 的隐藏完整工具池，仅供 Agent 派发子 Agent 时筛选工具使用。
     */
    @JSONField(serialize = false)
    ToolCollection subAgentToolCollection;

    /**
     * 会话工作区根目录（cwd 模式，供 workspace_* 工具使用）。
     */
    String workspaceRoot;

    /**
     * 当前 Agent 的 workspace_read 状态（path + range + mtime）。
     */
    @Builder.Default
    @JSONField(serialize = false)
    volatile Map<String, WorkspaceFileReadState> workspaceReadStateByPath = new ConcurrentHashMap<>();

    /**
     * 当前 Agent 已加载的 OpenUI 组件目录版本（目录内容 hash）。
     * null 表示尚未加载；子 Agent 与并行 fork 必须保持独立。
     */
    @JSONField(serialize = false)
    volatile String loadedOpenUiCatalogVersion;

    /**
     * Cute 运行时依赖包。
     * 所有 Agent / Tool / LLM 必须通过这里读取运行时协作者，禁止自行回 Spring 容器查找。
     */
    @JSONField(serialize = false)
    CuteRuntimeDependencies runtimeDependencies;

    // ---- 4. 提示词与文件上下文（构造时确定）----
    /**
     * 日期时间信息（格式化字符串）
     * 用途：
     * 1. 会话上下文：由 BaseAgent 作为 session_env 用户消息预置，让 LLM 感知当前时间；
     * 2. 数据溯源：标记文件/任务的时间维度信息；
     * 格式示例："2026-02-19 15:30:00"
     */
    String dateInfo;

    /**
     * 基础提示词模板（核心指令模板）
     * 用途：
     * 模板复用：作为智能体的核心指令模板，可替换占位符生成最终的系统提示词；
     */
    String basePrompt;

    // ---- 5. 跨轮记忆（run 前 hydrate）----
    /**
     * 跨轮工作记忆消息链（ledger hydrate 结果），run 前 preload 进 Memory。
     */
    List<Message> workingMemoryMessages;

    /**
     * 用户级策展记忆归属（LTM）。
     */
    LtmOwner ltmOwner;

    /**
     * 本轮深度记忆 prefetch 围栏文本（user 侧注入，非 system）。
     */
    String ltmMemoryContext;

    /**
     * 为 true 时禁止 memory tool 写用户画像（子代理等）。
     */
    @Builder.Default
    Boolean skipMemory = Boolean.FALSE;

    /**
     * LTM fork（flush/review）专用：允许 memory tool 写入，但禁止再 sync/再调度 review/prefetch 副作用。
     * 跳过外部 provider 污染，同时仍可写 builtin memory。
     */
    @Builder.Default
    Boolean ltmSideEffectsDisabled = Boolean.FALSE;

    /**
     * working_memory 投影作用域：main 或 sub:{agentId}。
     */
    @Builder.Default
    String memoryScope = WorkingMemoryScopes.MAIN;

    // ---- 6. 工具产物、执行账本与子 Agent 嵌套（run 内演化）----
    /**
     * 当前请求运行期的工具产物登记簿。
     * 这是工具文件来源的唯一事实来源。
     */
    @Builder.Default
    @JSONField(serialize = false)
    ToolArtifactRegistry toolArtifactRegistry = new ToolArtifactRegistry();

    /**
     * 当前线程绑定的工具来源快照。
     * 同步工具直接读取；异步工具必须在 execute 阶段捕获后显式传递到回调线程。
     */
    @Builder.Default
    @JSONField(serialize = false)
    ThreadLocal<ToolArtifactSource> currentToolArtifactSourceHolder = new ThreadLocal<>();

    /**
     * 当前请求的执行账本写入器。
     * 根节点初始化后挂入，LLM / BaseAgent / Summary 等运行时统一复用。
     */
    @JSONField(serialize = false)
    AgentExecutionRecorder executionRecorder;

    /**
     * 当前请求的 run 级运行态。
     * 统一保存 runId、LLM 顺序号和 toolCallId 映射，并兼容并发 executor 的线程内视图。
     */
    @Builder.Default
    @JSONField(serialize = false)
    AgentRunState agentRunState = AgentRunState.builder().build();

    /**
     * 当前 Agent 独立的上下文 token tracker。
     * 不放入共享 {@link AgentRunState}，避免父 Agent、子 Agent 与 LTM fork 互相污染。
     */
    @Builder.Default
    @JSONField(serialize = false)
    ContextTokenTracker contextTokenTracker = new ContextTokenTracker();

    /**
     * 子 Agent 嵌套展示元数据：父 Agent 工具的 toolCallId。
     * 仅 SubAgentContextFactory 写入；账本登记与 SSE 共用。
     */
    String parentToolUseId;

    /** 子 Agent 运行时 id（展示/账本） */
    String subAgentId;

    /** 子 Agent 类型 */
    String subAgentType;

    /** 子 Agent 任务短描述 */
    String subAgentDescription;

    /**
     * 子 Agent 对应的真实子会话 ID（{@code sub_{agentId}}）。
     * 仅 SubAgentContextFactory 写入；用于把子帧路由到独立子会话流（Phase 3）。
     */
    String subAgentChildSessionId;

    // ---- 7. 运行控制（控制面，run 内演化）----
    /**
     * 是否流式响应
     * 用途：
     * 1. 响应模式控制：true=流式输出（逐字返回结果，提升用户体验），false=一次性返回结果；
     * 2. LLM调用适配：控制LLM的调用模式（流式/非流式）；
     * 核心场景：聊天类Agent优先设为true，批量任务类Agent设为false
     */
    Boolean isStream;

    /**
     * 流式消息类型
     * 用途：
     * 1. 前端适配：标识流式返回的消息分类，前端可按类型展示不同样式（如plan_thought=思考过程、task=任务步骤）；
     * 2. 消息过滤：按类型筛选/处理不同的流式消息；
     * 枚举值示例："plan_thought"、"task"、"plan"、"result"
     */
    String streamMessageType;

    /**
     * Plan Mode 状态（EnterPlanMode / ExitPlanMode）。
     */
    @Builder.Default
    @JSONField(serialize = false)
    PlanModeState planModeState = PlanModeState.builder().build();

    /**
     * run 级控制面：取消令牌链、协作式关闭与注入队列。
     *
     * <p>这些状态由 run 外部的协调者（{@code /stop}、用户中途插话、ActiveRun 注册表）驱动，
     * 与 spec / state 的生命周期不同，因此独立成对象。<b>run 级独占</b>：父 Agent 与子 Agent
     * 各持一份，子 Agent finishRun / 覆盖任务令牌都不影响父；会话级共享状态在
     * {@link #sessionControl}。</p>
     *
     * <p>惰性：构造时可为 null，读取方经 {@link #control()} 获取后由本类负责补建。</p>
     */
    @Builder.Default
    @JSONField(serialize = false)
    RunControl control = new RunControl();

    /**
     * 可选的 run 级预算控制器。只有评测等需要硬门禁的入口会挂载；
     * 子 Agent 与并发 fork 共享同一实例，保证总成本口径一致。
     */
    @JSONField(serialize = false)
    RunBudget runBudget;

    /**
     * run 级工具访问策略。默认启用，子 Agent 共享，避免网络故障时反复重试。
     */
    @Builder.Default
    @JSONField(serialize = false)
    ToolAccessPolicy toolAccessPolicy = new ToolAccessPolicy();

    /**
     * 获取或懒创建 run 级控制面。
     */
    public synchronized RunControl control() {
        if (control == null) {
            control = new RunControl();
        }
        return control;
    }

    /**
     * 会话级共享控制面：Todo 任务列表与后台任务注册表。
     *
     * <p><b>父子 Agent 共享同一实例</b>（TaskStop / TaskOutput / TaskCreate 必须命中子
     * Agent），由 spawn 方在 {@code SubAgentContextFactory} 用父 sessionKey eager 物化——
     * 子 Agent 的 sessionId 是 {@code sub_<agentId>}，惰性补建会把子 key 绑进共享面。</p>
     */
    @Builder.Default
    @JSONField(serialize = false)
    SessionControl sessionControl = new SessionControl();

    /**
     * 获取或懒创建会话级共享控制面。并发约束见 {@link SessionControl}（以其实例为 monitor）。
     */
    public synchronized SessionControl sessionControl() {
        if (sessionControl == null) {
            sessionControl = new SessionControl();
        }
        return sessionControl;
    }

    /**
     * 获取或创建会话 Todo 列表（listId 优先 sessionId）。
     * 绑定与补建以 {@link SessionControl} 实例为 monitor（父子是不同 AgentContext，
     * 本类的 monitor 不能保护共享面）。
     */
    public SessionTaskListStore requireSessionTaskList() {
        // Todo 列表按 sessionId 建立稳定归属；没有会话 ID 时退回 requestId，保证
        // 临时/测试上下文也不会共享默认列表。
        String listId = sessionId != null && !sessionId.isBlank() ? sessionId : requestId;
        if (listId == null || listId.isBlank()) {
            listId = "default";
        }
        return sessionControl().requireSessionTaskList(listId, resolveTasklistPersistence());
    }

    /**
     * 只读取已存在的会话 Todo 列表，不触发 {@link #requireSessionTaskList()} 的惰性补建与 DB hydrate。
     * <p>发布快照等只读路径应在列表尚未建立时直接跳过，而不是为了发空快照去读库。</p>
     */
    public SessionTaskListStore peekSessionTaskList() {
        return sessionControl.getSessionTaskList();
    }

    public RuntimeBackgroundTaskRegistry requireBackgroundTasks() {
        // 进程内按 session 共享 registry，保证跨轮 TaskOutput 能命中仍在跑的后台任务。
        return sessionControl().requireBackgroundTasks(sessionId, requestId, resolveTasklistPersistence());
    }

    private TasklistPersistencePort resolveTasklistPersistence() {
        if (runtimeDependencies == null) {
            return null;
        }
        return runtimeDependencies.getOptionalTasklistPersistencePort();
    }

    // ---- 白名单 setter：仅 run 内确实需要演化的字段 ----
    // 这里刻意不提供 query / basePrompt / dateInfo / agentType /
    // disabledSkillNames / disabledMcpIds / toolDispatchWhitelist 等构造期字段的 setter：
    // 它们在装配完成后不应再被改写。
    // requestId 是唯一例外：resume 路径必须换成续跑 requestId，见下方 setRequestId。
    // 新增字段若确需运行期可变，必须先确认其生命周期归属，再在此登记。

    /** 装配期绑定主工具池（builder 之后，工具池构造依赖 context 自身，无法在 builder 内完成）。 */
    public void setToolCollection(ToolCollection toolCollection) {
        this.toolCollection = toolCollection;
    }

    /** 装配期绑定子 Agent 完整工具池（PlanSolve 双池形态）。 */
    public void setSubAgentToolCollection(ToolCollection subAgentToolCollection) {
        this.subAgentToolCollection = subAgentToolCollection;
    }

    /** resume 路径把 hydrate 出的历史消息注入子 Agent。 */
    public void setWorkingMemoryMessages(List<Message> workingMemoryMessages) {
        this.workingMemoryMessages = workingMemoryMessages;
    }

    /** LTM bootstrap 在 run 前注入围栏文本（可为 null 清除）。 */
    public void setLtmMemoryContext(String ltmMemoryContext) {
        this.ltmMemoryContext = ltmMemoryContext;
    }

    /** LTM bootstrap 注入用户级记忆归属。 */
    public void setLtmOwner(LtmOwner ltmOwner) {
        this.ltmOwner = ltmOwner;
    }

    /** 工具装配期懒解析工作区根（resolveAndEnsureRoot 依赖 sessionId）。 */
    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /** resume 需要唯一 requestId，否则 working_memory persist 幂等跳过。 */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** 子 Agent 的 working_memory 投影作用域。 */
    public void setMemoryScope(String memoryScope) {
        this.memoryScope = memoryScope;
    }

    /** 测试与装配路径补齐运行时依赖。 */
    public void setRuntimeDependencies(CuteRuntimeDependencies runtimeDependencies) {
        this.runtimeDependencies = runtimeDependencies;
    }

    /** 测试与装配路径补齐用户身份。 */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 测试路径替换 run 级状态（独立 ledger 视图）。 */
    public void setAgentRunState(AgentRunState agentRunState) {
        this.agentRunState = agentRunState;
    }

    /** 测试路径构造差集禁用清单。 */
    public void setDisabledMcpIds(java.util.Set<String> disabledMcpIds) {
        this.disabledMcpIds = disabledMcpIds;
    }

    /**
     * React 主 Agent 在未显式设置时兜底为 {@code agent_stream}。
     * 本 setter 会直接替换当前值；调用方只能在需要设置该帧类型时调用，
     * 子 Agent / LTM fork 已预置的值不得再被无条件覆盖。
     */
    public void setStreamMessageType(String streamMessageType) {
        this.streamMessageType = streamMessageType;
    }

    /**
     * 当前 Agent 执行的具体任务（结构化 query）。
     * 并行子任务与 PlanSolve 分步都会在 run 内改写，因此不是构造期字段。
     */
    public void setTask(String task) {
        this.task = task;
    }

    public PlanModeState requirePlanModeState() {
        // Plan Mode 是请求级状态机，缺省值在首次读取时补齐，避免空上下文让
        // Enter/ExitPlanMode 的行为依赖具体装配路径。
        if (planModeState == null) {
            planModeState = PlanModeState.builder().build();
        }
        return planModeState;
    }

    public boolean isSubAgent() {
        return subAgentId != null && !subAgentId.isBlank();
    }

    // ---- 运行时依赖 facade：工具不再各自复制「取可选依赖 + null 检查」的样板 ----
    // 依赖在装配期已挂到 runtimeDependencies 上，这里统一给出与 context 同源的访问入口。

    /**
     * 必需的运行时依赖包：缺席即装配错误，直接抛出。
     *
     * <p>本方法是「依赖是否装配」这一判断的<b>唯一</b>落点。工具不要再各自写
     * {@code if (ctx == null || ctx.getRuntimeDependencies() == null) throw ...}：
     * 那段样板曾逐字复制 15 份，只有类名不同。</p>
     */
    public CuteRuntimeDependencies requireRuntimeDependencies() {
        if (runtimeDependencies == null) {
            throw new IllegalStateException("AgentContext 缺少 CuteRuntimeDependencies");
        }
        return runtimeDependencies;
    }

    /** 子 Agent 派发器；未装配时返回 null。 */
    public org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner subAgentRunner() {
        return runtimeDependencies == null ? null : runtimeDependencies.getOptionalSubAgentRunner();
    }

    /** 活跃 run 注册表（TaskStop / 中断定位）；未装配时返回 null。 */
    public org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry activeAgentRunRegistry() {
        return runtimeDependencies == null ? null : runtimeDependencies.getOptionalActiveAgentRunRegistry();
    }

    /** Plan Mode 产物存储；未装配时返回 null。 */
    public org.wwz.ai.domain.agent.runtime.planmode.PlanArtifactStore planArtifactStore() {
        return runtimeDependencies == null ? null : runtimeDependencies.getOptionalPlanArtifactStore();
    }

    public SessionInboxPort sessionInboxPort() {
        return runtimeDependencies == null ? null : runtimeDependencies.getOptionalSessionInboxPort();
    }

    // ---- 控制面 facade：委托给 {@link RunControl}，保持调用方签名不变 ----
    public void markTurnClosed() {
        control().markTurnClosed();
    }

    public boolean isTurnClosed() {
        return control().isTurnClosed();
    }

    public boolean isRunCancelled() {
        return control().isCancelled();
    }

    public String getRunCancelReason() {
        return control().getCancelReason();
    }

    public RunCancellation getRunCancellation() {
        return control().getCancellation();
    }

    /**
     * 本轮取消令牌链的完整上游（任务级 + 用户 stop）。
     *
     * <p>派生子 Agent 时透传必须用本方法：{@link #getRunCancellation()} own 优先，
     * 后台 Agent 覆盖任务级令牌后会交出任务令牌而非用户 stop 令牌。</p>
     */
    public List<RunCancellation> getRunCancellationChain() {
        return control().cancellationChain();
    }

    /**
     * 把在途模型请求或阻塞调用挂到本轮取消令牌上。没有令牌时返回空注销句柄。
     */
    public Runnable registerRunAbort(Runnable hook) {
        if (hook == null) {
            return () -> {
            };
        }
        return control().registerAbort(hook);
    }

    /**
     * 绑定与 ActiveRun 共享的 inject 队列（控制面，不 begin 新 run）。
     */
    public void bindPendingInjectQueue(ConcurrentLinkedQueue<PendingInjectMessage> queue) {
        control().bindPendingInjectQueue(queue);
    }

    public ConcurrentLinkedQueue<PendingInjectMessage> requirePendingInjects() {
        return control().requirePendingInjects();
    }

    public void setRunBudget(RunBudget runBudget) {
        this.runBudget = runBudget;
    }

    public void offerInject(PendingInjectMessage message) {
        control().offerInject(message);
    }

    /**
     * 取出并清空待注入消息（步进边界调用）。
     */
    public List<PendingInjectMessage> drainPendingInjects() {
        ConcurrentLinkedQueue<PendingInjectMessage> queue = requirePendingInjects();
        List<PendingInjectMessage> drained = new ArrayList<>();
        PendingInjectMessage next;
        while ((next = queue.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    public String resolveMemoryScope() {
        return WorkingMemoryScopes.normalize(memoryScope);
    }

    public void bindCurrentToolArtifactSource(ToolArtifactSource toolArtifactSource) {
        // 当前来源通过 ThreadLocal 绑定到一次工具调用；异步回调不能隐式依赖
        // 线程切换后的值，必须在创建回调时显式捕获并传递 source。
        currentToolArtifactSourceHolder.set(toolArtifactSource);
    }

    public void clearCurrentToolArtifactSource() {
        currentToolArtifactSourceHolder.remove();
    }

    public ToolArtifactSource requireCurrentToolArtifactSource(String toolName) {
        ToolArtifactSource source = currentToolArtifactSourceHolder.get();
        if (source == null) {
            throw new IllegalStateException("Missing current tool artifact source for tool: " + toolName);
        }
        return source;
    }

    /**
     * 读取当前线程绑定的工具来源快照。
     * 主要用于前端实时事件补齐 toolCallId / toolName 等关联信息。
     */
    public ToolArtifactSource getCurrentToolArtifactSource() {
        return currentToolArtifactSourceHolder.get();
    }


    public void markWorkspaceFileRead(WorkspaceFileReadState state) {
        if (state == null || state.getAbsolutePath() == null || state.getAbsolutePath().isBlank()) {
            return;
        }
        if (workspaceReadStateByPath == null) {
            synchronized (this) {
                if (workspaceReadStateByPath == null) {
                    workspaceReadStateByPath = new ConcurrentHashMap<>();
                }
            }
        }
        workspaceReadStateByPath.put(state.getAbsolutePath(), state);
    }

    public WorkspaceFileReadState getWorkspaceFileReadState(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank() || workspaceReadStateByPath == null) {
            return null;
        }
        return workspaceReadStateByPath.get(absolutePath);
    }

    public boolean hasWorkspaceFileBeenRead(String absolutePath) {
        return getWorkspaceFileReadState(absolutePath) != null;
    }

    public Map<String, WorkspaceFileReadState> snapshotWorkspaceReadState() {
        if (workspaceReadStateByPath == null || workspaceReadStateByPath.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(workspaceReadStateByPath);
    }

    /**
     * 压缩后丢弃 dedup 状态：旧 workspace_read 正文可能已不在上下文中，
     * 保留状态会导致再次读取仍返回 unchanged stub。
     */
    public void clearWorkspaceReadState() {
        if (workspaceReadStateByPath != null) {
            workspaceReadStateByPath.clear();
        }
    }

    public String getLoadedOpenUiCatalogVersion() {
        return loadedOpenUiCatalogVersion;
    }

    public void setLoadedOpenUiCatalogVersion(String loadedOpenUiCatalogVersion) {
        this.loadedOpenUiCatalogVersion = loadedOpenUiCatalogVersion;
    }


    public ToolArtifactBinding registerGeneratedArtifact(ToolArtifactSource source, File file) {
        // 工具产物登记到唯一 registry；前端可见列表由 ledger/toolCall 绑定派生，
        // 不再另维护会话级兼容文件列表。
        return toolArtifactRegistry.registerGeneratedFile(source, file);
    }

    public List<ToolArtifactBinding> getArtifactBindingsByToolCallId(String toolCallId) {
        return toolArtifactRegistry.findBindingsByToolCallId(toolCallId);
    }

    public List<ToolArtifactBinding> getVisibleArtifactBindings() {
        return toolArtifactRegistry.listVisibleBindings();
    }

    public List<File> getVisibleArtifactFiles() {
        return getVisibleArtifactBindings().stream()
                .map(ToolArtifactBinding::getFile)
                .toList();
    }

    /**
     * 绑定本次请求的 run 主键与外部身份。
     */
    public void activateLedgerRun(Long runId, String runUid) {
        // run 激活只绑定账本身份，不重置上下文中的会话、工具或文件状态；后续
        // LLM/tool 记录点通过同一个 AgentRunState 取得顺序号和当前执行位置。
        ensureAgentRunState().setRunId(runId);
        ensureAgentRunState().setRunUid(runUid);
    }

    /**
     * 标记当前线程所在的 agent 与步号。
     * 这样 LLM 和工具记录点无需知道具体是哪一种 Agent 实现。
     */
    public void markExecutionPosition(String agentName, Integer stepNo) {
        ensureAgentRunState().markExecutionPosition(agentName, stepNo);
    }

    /**
     * 当前上下文是否已具备可用的执行账本能力。
     */
    public boolean hasActiveLedgerRun() {
        return executionRecorder != null
                && agentRunState != null
                && agentRunState.getRunId() != null;
    }

    /**
     * 为并发子任务创建轻量上下文分叉。
     * child context 共享 run 级依赖与账本事实，但复制任务态兼容视图，避免并发写回父上下文。
     */
    public AgentContext forkForParallelTask(String parallelTask) {
        // 并行子任务共享 printer、runtime、registry 和 ledger run 身份，以便事实
        // 仍归属于同一运行；任务文件、workspace 读状态和 ThreadLocal 则隔离，
        // 防止子任务互相覆盖当前任务视图或工具来源。
        return AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .userId(userId)
                .query(query)
                .task(parallelTask)
                .printer(printer)
                .runtimeDependencies(runtimeDependencies)
                .dateInfo(dateInfo)
                .workspaceRoot(workspaceRoot)
                // 工具池与模型开关是 think/tool 循环的运行前提：fork 若不继承，
                // 子任务会拿到 null toolCollection，或用默认模型配置调用 LLM。
                .toolCollection(toolCollection)
                .model(model)
                .thinking(thinking)
                .thinkingEffort(thinkingEffort)
                .subAgentToolCollection(subAgentToolCollection)
                .workspaceReadStateByPath(new ConcurrentHashMap<>())
                // 并行 fork 与父 context 共用 workspace，但模型上下文独立，不能继承目录已加载标记。
                .loadedOpenUiCatalogVersion(null)
                .isStream(isStream)
                .streamMessageType(streamMessageType)
                .basePrompt(basePrompt)
                .workingMemoryMessages(workingMemoryMessages)
                .agentType(agentType)
                .toolArtifactRegistry(toolArtifactRegistry)
                .currentToolArtifactSourceHolder(new ThreadLocal<>())
                .executionRecorder(executionRecorder)
                .agentRunState(agentRunState)
                .planModeState(planModeState)
                // 安全门禁必须从父上下文继承：fork 出来的并行任务不能因为重新 build
                // 就丢掉工具白名单、禁用 skill/MCP、skipMemory 与记忆作用域。
                .toolDispatchWhitelist(toolDispatchWhitelist)
                .disabledSkillNames(disabledSkillNames)
                .disabledMcpIds(disabledMcpIds)
                .skipMemory(skipMemory)
                .ltmSideEffectsDisabled(ltmSideEffectsDisabled)
                .memoryScope(memoryScope)
                // run 级控制面必须独立：fork 是并发子任务，它的 finishRun 不得关闭父回合，
                // 也不得与父共用 inject 队列（否则并发子任务的步进边界会互相吃掉对方的指导）。
                // 父的取消令牌经 parentCancellation 只读透传：/stop 仍杀得到并发子任务。
                // 会话级控制面共享：TaskList / 后台任务表必须命中同一份。
                .control(new RunControl().withParentCancellation(getRunCancellation()))
                .runBudget(runBudget)
                .toolAccessPolicy(toolAccessPolicy)
                .sessionControl(sessionControl())
                .build();
    }

    private synchronized AgentRunState ensureAgentRunState() {
        if (agentRunState == null) {
            agentRunState = AgentRunState.builder().build();
        }
        return agentRunState;
    }

    public synchronized ContextTokenTracker contextTokenTracker() {
        if (contextTokenTracker == null) {
            contextTokenTracker = new ContextTokenTracker();
        }
        return contextTokenTracker;
    }

    /**
     * 刻意只拼请求身份与轻量字段。不改为 {@code @ToString}：
     * control（取消令牌/注入队列/任务表）、运行时依赖、工具池这些字段
     * 要么体量大要么可变，进日志只会产生噪音和误导。
     * 新增字段时若属排查必需，在此显式追加，不要全量拼接。
     */
    @Override
    public String toString() {
        return "AgentContext(" +
                "requestId='" + requestId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", query='" + query + '\'' +
                ", task='" + task + '\'' +
                ", dateInfo='" + dateInfo + '\'' +
                ", isStream=" + isStream +
                ", streamMessageType='" + streamMessageType + '\'' +
                ", agentType=" + agentType +
                ')';
    }
}
