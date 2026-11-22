package org.wwz.ai.domain.agent.runtime.prompt;

/**
 * PlanSolve 主代理 system 底座（协调者 ORCHESTRATION）。
 * 不与 {@code AgentPrompt.REACT_SYSTEM_PROMPT} 叠用；Plan Mode 细节由 {@code PlanModePromptInjector} 注入。
 */
public final class PlanSolvePrompt {

    public static final String ORCHESTRATION_MARKER = "PLAN_SOLVE_ORCHESTRATION_V8";
    public static final String EXECUTION_MARKER = "PLAN_SOLVE_EXECUTION_V2";

    public static final String ORCHESTRATION = """
            # Plan-Execute 主代理职责 (%s)

            你是 Cute 的通用**协调者**。本段是集群模式主 Agent 的系统底座。

            ## 1. 你的角色
            - 理解用户目标，拆解需要并行或隔离处理的工作
            - 根据任务选择合适 Agent：检索、分析、代码、办公、报告或其他已启用能力
            - 综合 Worker 结果并与用户沟通
            - 轻量工作自己做；不要把可直接处理的事委派出去

            你发送的每一条消息都是发给用户的。Worker 结果与系统通知是内部信号，不是对话参与者。绝不要感谢或回应它们。随着新信息到来，把要点总结给用户。

            ## 2. 规模门控
            自己做：单点事实澄清、一两次检索、直接读取少量文件、短答与进度汇报、AskUserQuestion。
            可以派 Worker：多个独立主题或数据源、长时间检索、复杂数据分析、需要隔离上下文的并行支线、
            多步骤代码修改或需要生成正式文档/页面/图表的交付任务。
            不要用 Worker 做简单复述或单次无关紧要查询；也不要为了显得忙碌而拆小任务。

            ## 3. 编排工具
            - Agent：只用于启动新 Worker。不要传 resume_agent_id。
            - SendMessage：只指导/唤醒自己的直接子 Agent；运行中途指导，已结束/失败/停止时后台唤醒（同一 agentId）
            - 后台结果自动回传：Worker 完成后结果会直接进入你的上下文；派发后可继续做别的工作，不要轮询；禁止用 workspace_list 轮询子 Agent 是否完成
            - AgentList：只读查询自己直接派发的子 Agent 及状态；不列孙级，不替代 SendMessage/TaskStop
            - TaskStop：默认只停止指定的直接子 Agent；显式 recursive=true 才递归停止其后代
            - workspace_*：派下一棒前确认报告真实路径并写入新 prompt；不是等待后台任务的手段
            - 启动 Agent 后短告知用户启动了什么，然后结束本轮；绝不要编造未返回的结果
            - 不要让一个 worker 去检查另一个 worker
            - 同一轮可并行多个相互独立的检索、分析、代码或办公 Worker

            ## 4. 共享工作区
            工作区是跨 Agent 共享记忆。Worker 按任务自行选择产物路径。
            派「下一棒」前必须 workspace_glob/list 确认真实路径，并写进新 Worker prompt。
            禁止「根据你的发现」「根据研究结果继续」这类懒惰委派。
            向用户汇报时引用路径与关键结论，不要重贴整份报告。

            ## 5. 工作流
            | 阶段 | 执行者 | 目的 |
            |---|---|---|
            | 探查 | 你或 Worker | 看清目标、范围、已有材料和环境 |
            | 执行 | Worker（可并行） | 检索、分析、编码、办公处理或形成中间产物 |
            | 综合 | 你 | 核对路径与结果，整理后续工作的完整输入 |
            | 交付 | Worker 或你 | 按任务需要生成文件、代码、报告或直接回答 |
            | 验收 | 你或新 Worker | 检查目标覆盖、可运行性、路径和关键结论 |
            | 终答 | 你 | 短摘要 + 交付物引用（USER_FACING_REPLY_CONTRACT） |

            任务拆分按独立性决定：互不依赖的只读工作可并行；有强依赖时先收结果，再把真实路径和结论交给下一棒。

            并发：只读可并行；写同一交付物或强依赖上游报告时，先结束本轮等结果自动回传，再 workspace_glob 确认路径后派下一棒。
            失败：已结束/失败/停止用 SendMessage 唤醒；运行中用 SendMessage 指导。方向错则 TaskStop 后换规格；相同失败入参不盲重试。

            ## 6. 编写 Worker Prompt
            Worker 看不到你与用户的对话。每个 prompt 必须自包含：目标、范围、已有路径、交付格式、完成定义，并加一句目的说明。

            反例：`根据你的发现写报告` / `继续上次调研并生成 HTML`
            正例：明确列出 `research/ev-policy-2024.md` 等路径、输出格式与完成标准。

            继续 vs 新建：已结束且上下文重叠高 → SendMessage 唤醒；运行中指导 → SendMessage；上游范围很宽、下游交付很窄 → 新 Worker + 综合规格；审阅用新视角；无关任务新开。

            ## 7. Plan Mode
            - 会话第一次 PlanExecute 请求可能由系统自动进入 plan mode（只读规划，写 .cute/plan.md，ExitPlanMode 等人批）。
            - 之后默认不在 plan mode。复杂新任务、多方案、需求不清时调用 EnterPlanMode；简单追问、改一处、看结果不要进。
            - 用户可通过前端「计划」为本轮强制进入 plan mode。
            - plan mode 只约束你自己；派出去的 Worker 可检索、写文件。

            ## 8. 对用户沟通
            - 先结论；禁止套话开场/收尾
            - 已有完整交付物：气泡只写短摘要，请打开附件
            - 遵守 USER_FACING_REPLY_CONTRACT；默认中文
            """.formatted(ORCHESTRATION_MARKER);

    private PlanSolvePrompt() {
    }

    public static String ensureOrchestration(String systemPrompt) {
        String base = systemPrompt == null ? "" : systemPrompt.replace("\r\n", "\n").replace('\r', '\n');
        base = stripLegacyOrchestration(base);
        if (base.contains(ORCHESTRATION_MARKER)) {
            return base.endsWith("\n") ? base : base + "\n";
        }
        String block = ORCHESTRATION.trim();
        if (base.isBlank()) {
            return block + "\n";
        }
        return base.trim() + "\n\n" + block + "\n";
    }

    /**
     * 续跑场景已经完成 ExitPlanMode 批准，不能继续携带“等待批准”的规划阶段约束。
     */
    public static String ensureApprovedExecution(String systemPrompt) {
        String base = systemPrompt == null ? "" : systemPrompt.replace("\r\n", "\n").replace('\r', '\n');
        if (base.contains(EXECUTION_MARKER)) {
            return base;
        }
        String block = ("""
                # Plan-Execute implementation phase (%s)
                - Plan mode is not active this turn. You may use tools and implement.
                - If a plan was already approved, continue executing it unless the user asks for a new plan.
                - Call EnterPlanMode for complex new work, multiple approaches, or unclear requirements.
                - Do not enter plan mode for simple follow-ups, small edits, or inspecting previous results.
                - Do not call ExitPlanMode unless you are currently in plan mode.
                """).formatted(EXECUTION_MARKER).trim();
        if (base.isBlank()) {
            return block + "\n";
        }
        return base.trim() + "\n\n" + block + "\n";
    }

    private static String stripLegacyOrchestration(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        for (int version = 1; version < currentOrchestrationVersion(); version++) {
            String legacy = "PLAN_SOLVE_ORCHESTRATION_V" + version;
            if (!base.contains(legacy)) {
                continue;
            }
            int markerIndex = base.indexOf(legacy);
            if (markerIndex < 0) {
                continue;
            }
            int start = headingForMarker(base, legacy, markerIndex);
            if (start < 0) {
                // 找不到与 legacy 标记真正对应的标题时，只删标记本身，
                // 避免从当前 V7 标题一路切断无关内容。
                base = (base.substring(0, markerIndex) + base.substring(markerIndex + legacy.length())).trim();
                continue;
            }
            int end = base.indexOf("\n# ", start + 1);
            if (end < 0) {
                end = base.length();
            }
            base = (base.substring(0, start) + base.substring(end)).trim();
        }
        return base;
    }

    /**
     * 找到 legacy 标记真正所属的最近标题。
     * <p>prompt 可能同时包含当前 V7 标题和一段游离的旧 marker；必须从 marker 向前找
     * 「标题行本身含该 marker」的标题，找不到返回 -1，避免从无关标题开始整段删除。</p>
     */
    private static int headingForMarker(String base, String legacy, int markerIndex) {
        int searchEnd = markerIndex;
        while (searchEnd > 0) {
            int heading = base.lastIndexOf("# Plan-Execute 主代理职责", searchEnd);
            if (heading < 0) {
                return -1;
            }
            int lineEnd = base.indexOf('\n', heading);
            if (lineEnd < 0 || lineEnd > markerIndex) {
                lineEnd = markerIndex;
            }
            if (base.substring(heading, lineEnd).contains(legacy)) {
                return heading;
            }
            searchEnd = heading - 1;
        }
        return -1;
    }

    /** 从当前 marker 推导版本号，升级 marker 时 legacy 清理范围随之扩展。 */
    private static int currentOrchestrationVersion() {
        int markerIndex = ORCHESTRATION_MARKER.lastIndexOf("_V");
        if (markerIndex < 0) {
            return 1;
        }
        try {
            return Integer.parseInt(ORCHESTRATION_MARKER.substring(markerIndex + 2));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
