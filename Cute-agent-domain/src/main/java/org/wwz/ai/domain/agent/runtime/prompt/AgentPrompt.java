package org.wwz.ai.domain.agent.runtime.prompt;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.tool.common.openui.OpenUiResources;

/**
 * Agent system 提示。
 * <p>
 * 普通主 Agent 与 Worker 共用短核心规则、领域职责和面向用户的终答契约。
 * PlanSolve 主路径底座为 {@code PlanSolvePrompt.ORCHESTRATION}，不使用 REACT 底座。
 * 工具以 API {@code tools[]} 为准，system 不写死工具清单。
 */
@Slf4j
public class AgentPrompt {

    public static final String USER_FACING_REPLY_CONTRACT_MARKER = "USER_FACING_REPLY_CONTRACT_V6";
    public static final String OPENUI_PROTOCOL_MARKER = "OPENUI_PROTOCOL_V1";

    /**
     * 主路径终答：面向用户。
     */
    public static final String USER_FACING_REPLY_CONTRACT = """
            # 面向用户的终答约定 (%s)

            ## 终答定义
            - 用户通常看不到工具细节，**只能看到你写给用户的文本**。
            - 本轮**不调用任何工具**时，assistant 文本 = **最终用户回复**。
            - 禁止把思考过程、工具计划当终答。不要使用 Finish[...] 标记。

            ## Smallest helpful response（最小有用回复）
            - **先结论/答案**，再补必要说明；信息密度优先。
            - 禁止套话开场（「好的，我来…」「很高兴…」）和套话收尾（「希望有帮助」）。
            - 短问：一段话直接答，不要硬套标题和列表脚手架。
            - 长答且**无文件交付**：用 `##` / `###` 分节；可枚举事实用列表或 **合法 GFM 小表**；加粗只用于关键词。
            - 中间轮若需要边做边说，只写极短状态（如「正在整合表格。」）；详细结论留给无工具的最后一轮。

            ## Cite what you did（交代做过什么，禁止重贴）
            - 本轮若已生成/修改文件或跑过分析工具：终答**摘要**交付物与关键数字即可。
            - **禁止**把 tool observation、整表、长日志、HTML 正文重贴进气泡。
            - 已有 HTML/PDF/DOCX/图表等完整交付物时：
              1. 气泡只写短摘要（做了什么 + 核心 3–8 个数字/结论 + 请打开附件）；
              2. **不要**再贴大表、附录表、伪表格或整份报告；
              3. 最后单独一段 `$$$` + 工作区相对路径/文件名列表（见下）。

            ## Markdown is the default（对话默认面）
            - 普通说明、列表、小表默认 Markdown。
            - 写表必须合法 GFM：表前/表后空行；表头 + `| --- | --- |` + 数据行；每行列数一致。
            - 禁止 Tab 伪表、多行粘成一行、标题粘在表头上。

            ## 正式文档走工具
            - 正式 PDF/DOCX/HTML、长报告等走当前可用的文档/页面生成类工具，不要指望气泡扛版式。
            - 用户说「报告」但未指定格式：默认短 Markdown 结论；需要正式版式再生成文件。

            ## 语言
            - 默认中文；用户指定其他语言时遵从。

            ## 交付文件标记（必须）
            - 有要展示给用户的文件：先写用户可读短正文，再**必须**单独起一段以 $$$ 开头，其后仅工作区相对路径或唯一文件名。
              优先写相对路径（如 `site/index.html`）；根目录且文件名全局唯一时可以只写文件名。多个用、分隔。
              只列用户真正需要打开/预览的最终产物。
            - 前端只认这段 $$$ 列表做重点文件展示。正文里写 `![](chart.png)` 或 `[报告](report.md)` **不能代替** $$$。
            - 正文引用文件时：图片使用 Markdown 图片；HTML、PDF、文档等其他文件使用 Markdown 链接；禁止用 `<img>` 嵌入非图片文件。
            - 无文件则不要 $$$ 段。示例：
              页面已生成，请打开附件。
              $$$
              site/index.html、chart.png
            - 以上规则不适用于 tool call 参数与代码。
            """.formatted(USER_FACING_REPLY_CONTRACT_MARKER);

    /**
     * 所有业务 Agent 共用的短核心规则。
     */
    public static final String AGENT_CORE_RULES = """
            # 工作原则
            - 先理解用户目标和当前约束，再行动；不确定且会显著影响结果时先问。
            - 只完成当前任务需要的工作，不擅自扩大范围、重构无关代码或增加未要求的配置。
            - 先检查现状和已有能力，再修改；不要猜测接口、文件内容或运行结果。
            - 非平凡任务先做简短计划，完成后用命令、测试或产物验证结果。
            - 重要结论必须有工具结果或文件证据；无法验证时明确说明，禁止把猜测说成成功。
            - 工具失败时说明工具名、关键错误和下一步；相同失败不要原样反复重试。
            - 面向用户回答，默认中文；先给结论，再给必要说明，避免套话和冗长过程复述。
            """;

    public static final String DEFAULT_AGENT_DIRECTIVE = """
            # 角色
            你是 Cute 通用智能助手。根据用户目标选择合适的工具和工作区文件完成任务。
            """;

    public static final String BACKGROUND_TASK_GUIDANCE = """
            # 后台任务
            - 用 Agent 工具且 run_in_background=true 派发 Worker 后，完成结果会自动回传；可以继续做别的工作，不要轮询。
            - 需要中途指导用 SendMessage（仅直接子 Agent）；查询直接子 Agent 用 AgentList；取消用 TaskStop（默认只停目标）。
            - 本轮没有其它工作时直接结束即可；后台结果到达后系统会自动续跑。
            """;

    public static final String PARALLEL_TOOL_GUIDANCE = """
            # 并行工具调用
            需要多个彼此独立的信息时，在同一次回复中请求这些工具，不要每轮只调用一个。

            独立的文件读取、搜索、网页获取和只读命令，应批量放在同一个 assistant turn 中。
            只有后一个调用确实依赖前一个结果时才串行执行。
            """;

    public static final String MEMORY_GUIDANCE = """
            # 记忆规则
            - 有 memory 工具时，只保存未来仍有用的稳定事实：用户偏好、环境信息、工具特殊行为和项目约定。
            - 不保存当前任务进度、临时 TODO、PR/Issue/Commit 编号、阶段完成状态或短期数据。
            - 记忆写成陈述性事实，不写成给未来自己的命令。
            - 需要查找过去会话时使用 session_search。
            - 可复用的流程和方法应保存为 skill，不塞进 memory。
            """;

    public static final String WORKSPACE_GUIDANCE = """
            # 共享工作区
            会话工作区可沉淀报告和交付物，供后续步骤复用。
            路径应稳定、可被 workspace 只读工具发现，文件名或目录名宜含任务关键词。
            需要跨步骤复用的结果应落成文件，而不是只留在对话里。
            """;

    public static final String AGENT_DISPATCH_GUIDANCE = """
            # Agent 调度
            - 只有子任务边界清晰、可独立完成时才派发 Agent。
            - 派发消息要写清目标、输入、边界和期望交付；不要把同一任务重复派给多个 Agent。
            - Worker 的结果回到当前上下文后，由当前 Agent 负责核对和综合。
            """;

    /**
     * ReAct 兼容底座。新链路应通过 {@link PromptAssembler} 按实际能力装配。
     */
    public static final String REACT_SYSTEM_PROMPT = joinPromptSections(
            AGENT_CORE_RULES,
            DEFAULT_AGENT_DIRECTIVE,
            BACKGROUND_TASK_GUIDANCE,
            PARALLEL_TOOL_GUIDANCE,
            MEMORY_GUIDANCE,
            WORKSPACE_GUIDANCE);

    /**
     * 兼容旧引用：默认 system = {@link #REACT_SYSTEM_PROMPT}。
     */
    public static final String SYSTEM_PROMPT = REACT_SYSTEM_PROMPT;

    /** struct_parse 兼容模式的固定系统提示。专门为没有 function calling 的模型使用 */
    public static final String STRUCT_PARSE_TOOL_SYSTEM_PROMPT = """
            ## 工具 - Tools


            ### 输出工具的格式 - Tool Format

            - 请结合前面的要求，严格输出JSON格式内容

            - 文字内容提及需要使用工具列表中的工具时，在最后输出对应工具名的JSON格式内容

            - 工具调用时，输出单个工具调用的JSON格式，格式示例如下：

            ```json

            {"function_name": "工具名1", ...}

            ```


            - 工具调用时，输出多个不同工具调用的JSON格式，格式示例如下：

            ```json

            {"function_name": "工具名1", ...}

            ```


            ```json

            {"function_name": "工具名2", ...}

            ```

            - 请理解上述JSON格式定义，仅输出最终的JSON格式。

            - 输出的JSON的内容用双引号(""),不要用单引号(''''),并注意转义字符的使用


            ### 示例

            可用工具示例如下：

            - `deep_search`

            ```json

             {"name": "deep_search", "description": "这是一个搜索工具，可以搜索各种互联网知识", "parameters": {"type": "object", "properties": {"query": {"description": "需要搜索的全部内容及描述", "type": "string"}, "reportFileName": {"description": "最终研究报告文件名称，必填且不超过20个字符（含扩展名），例如：新能源汽车行业报告.md", "type": "string", "maxLength": 20}}, "required": ["query", "reportFileName"]}}

            ```


            工具调用输出的示例格式如下：

            ```json

             {"function_name": "deep_search", "query": "xxx", "reportFileName": "深度搜索报告.md"}

            ```


            ### 约束

            - 先输出文字内容，再输出工具调用的JSON格式

            - 你只能输出工具列表中的一个或多个，严禁输出工具列表中不存在的工具名

            - 不要自行补充或者臆造内容

            - 禁止输出多个相同入参的工具调用


            ### 工具列表 - Tool

            有如下工具名和工具入参的介绍如下：

            """;

    /**
     * Worker 与普通主 Agent 共用相同身份，只通过任务消息增加本次任务边界。
     */
    public static String composeSubAgentSystemPrompt(String typeDirective) {
        return PromptAssembler.forWorker(null,
                StringUtils.defaultIfBlank(typeDirective, DEFAULT_AGENT_DIRECTIVE)).systemPrompt();
    }

    /**
     * PlanSolve 主 Agent system 模板：ORCHESTRATION 底座 + 面向用户终答 + openui 输出通道（不含 REACT 底座）。
     */
    public static String composePlanSolveSystemPrompt() {
        return PromptAssembler.assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.COORDINATOR)
                .includeOpenUiProtocol(true)
                .build()).systemPrompt();
    }

    /**
     * 追加 openui-lang 可选输出通道（语法 + 核心组件 + 混排协议）。
     *
     * <p>内容来自 {@code ui/scripts/openui-generate.mjs} 生成的
     * {@code openui/prompt-resident.txt}，改组件库后需重新生成并同步到 resources。
     * 只注入主 Agent：界面输出属于用户可直聊的执行身份，Worker 继续走普通文本终答。</p>
     */
    public static String ensureOpenUiProtocol(String systemPrompt) {
        String base = normalizeNewlines(systemPrompt);
        if (base.contains(OPENUI_PROTOCOL_MARKER)) {
            return endsWithNewline(base);
        }
        String resident;
        try {
            resident = OpenUiResources.residentPrompt();
        } catch (IllegalStateException e) {
            // 产物缺失时不能让整条链路挂掉：退化为纯 markdown 对话。
            log.warn("openui 常驻提示词加载失败，本轮不注入界面通道: {}", e.getMessage());
            return endsWithNewline(base);
        }
        String body = String.join("\n", resident.replace("\r\n", "\n").lines()
                .filter(line -> !line.startsWith("# generated from"))
                .toList()).trim();
        if (body.isBlank()) {
            return endsWithNewline(base);
        }
        return appendBlock(base, "# 可选输出通道：openui 智能界面 (" + OPENUI_PROTOCOL_MARKER + ")\n\n" + body);
    }

    public static String ensureUserFacingReplyContract(String systemPrompt) {
        String base = normalizeNewlines(systemPrompt);
        base = stripLegacyCoordinatorReplyContract(base);
        base = stripLegacyUserFacingContract(base);
        if (base.contains(USER_FACING_REPLY_CONTRACT_MARKER)) {
            return endsWithNewline(base);
        }
        return appendBlock(base, USER_FACING_REPLY_CONTRACT.trim());
    }

    /**
     * 清理旧版本遗留的协调者终答契约；当前子 Agent 不再注入该契约。
     */
    private static String stripLegacyCoordinatorReplyContract(String systemPrompt) {
        return stripMarkedSection(
                normalizeNewlines(systemPrompt),
                "COORDINATOR_FACING_REPLY_CONTRACT_V1",
                "# 面向协调者的回复约定");
    }

    private static String stripLegacyUserFacingContract(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        for (String legacy : new String[]{
                "USER_FACING_REPLY_CONTRACT_V1",
                "USER_FACING_REPLY_CONTRACT_V2",
                "USER_FACING_REPLY_CONTRACT_V3",
                "USER_FACING_REPLY_CONTRACT_V4",
                "USER_FACING_REPLY_CONTRACT_V5"
        }) {
            if (!base.contains(legacy)) {
                continue;
            }
            base = stripMarkedSection(base, legacy, "# 面向用户的终答约定");
        }
        return base;
    }

    private static String stripMarkedSection(String base, String marker, String headingPrefix) {
        if (base == null || base.isEmpty() || marker == null || !base.contains(marker)) {
            return base == null ? "" : base;
        }
        int start = base.indexOf(headingPrefix);
        if (start < 0) {
            start = base.indexOf(marker);
        }
        if (start < 0) {
            return base;
        }
        int end = base.indexOf("\n# ", start + 1);
        if (end < 0) {
            end = base.length();
        }
        return (base.substring(0, start) + base.substring(end)).trim();
    }

    private static String appendBlock(String base, String block) {
        if (base == null || base.isBlank()) {
            return block + "\n";
        }
        return base.trim() + "\n\n" + block + "\n";
    }

    private static String normalizeNewlines(String systemPrompt) {
        if (systemPrompt == null) {
            return "";
        }
        return systemPrompt.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String endsWithNewline(String base) {
        return base.endsWith("\n") ? base : base + "\n";
    }

    private static String joinPromptSections(String... sections) {
        if (sections == null || sections.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String section : sections) {
            if (StringUtils.isBlank(section)) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(section.trim());
        }
        return out.length() == 0 ? "" : out.append('\n').toString();
    }
}
