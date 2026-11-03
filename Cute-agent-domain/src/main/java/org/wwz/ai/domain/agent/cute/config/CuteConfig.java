package org.wwz.ai.domain.agent.cute.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * Cute Phase 1 期间保留在 domain 的过渡态共享配置契约。
 * 删除/迁移时机：当 Cute 共享配置被单独 change 收敛到 app 或专用配置模块后再迁移；
 * 当前阶段禁止顺手改写读取语义。
 */
@Slf4j
@Getter
@Configuration
public class CuteConfig {

    @Value("${autobots.autoagent.planner.model_name:}")
    private String plannerModelName;

    @Value("${autobots.autoagent.executor.model_name:}")
    private String executorModelName;

    @Value("${autobots.autoagent.react.model_name:}")
    private String reactModelName;

    @Value("${autobots.autoagent.tool.deep_search.file_desc.truncate_len:500}")
    private Integer deepSearchToolFileDescTruncateLen;

    @Value("${autobots.autoagent.tool.deep_search.message.truncate_len:500}")
    private Integer deepSearchToolMessageTruncateLen;

    @Value("${autobots.autoagent.tool.clear_tool_message:1}")
    private String clearToolMessage;

    @Value("${autobots.autoagent.deep_search_page_count:3}")
    private String deepSearchPageCount;

    /** 调用方必须只读；该 Map 由配置绑定整体替换，直接修改会绕过配置刷新语义。 */
    private Map<String, String> multiAgentToolListMap = new HashMap<>();
    @Value("${autobots.autoagent.tool_list:{}}")
    public void setMultiAgentToolList(String list) {
        this.multiAgentToolListMap = parseStringMap(list);
    }

    /**
     * PlanSolve 主 Agent 可见的工具白名单。完整 tool_list 仍作为子 Agent 的工具来源。
     * 注意：默认值必须包含 get_ui_components —— 主 agent 的 system prompt 里注入了 openui 通道，
     * 提到这个工具；只改 yml 无效（application-*.yml 里该键的层级与这里不一致）。
     */
    @Value("${autobots.autoagent.plan-solve-main-tool-list:Agent,TaskCreate,TaskGet,TaskUpdate,TaskList,TodoWrite,TaskStop,TaskOutput,SendMessage,EnterPlanMode,ExitPlanMode,AskUserQuestion,ToolSearch,ToolDescribe,ToolCall,ListMcpResources,ReadMcpResource,workspace_read,workspace_list,workspace_glob,workspace_grep,workspace_write,workspace_edit,code_mode,skill_view,skills_search,memory,session_search,image_ocr,canvas_publish,pdf_reader,pdf_structure,word_reader,text_processor,markdown_processor,html_processor,excel_reader,csv_processor,get_ui_components}")
    private String planSolveMainToolList;

    public void setPlanSolveMainToolList(String planSolveMainToolList) {
        this.planSolveMainToolList = planSolveMainToolList;
    }

    /**
     * MCP 工具搜索模式：always=有 deferred MCP 才注册三件套；standard/off=全量进 tools[]；auto=超过阈值才延迟。
     */
    @Value("${autobots.autoagent.mcp.tool-search-mode:always}")
    private String mcpToolSearchMode;

    public void setMcpToolSearchMode(String mcpToolSearchMode) {
        this.mcpToolSearchMode = mcpToolSearchMode;
    }

    /**
     * auto 模式下：deferred 候选数 ≥ 该阈值时启用 ToolSearch。
     */
    @Value("${autobots.autoagent.mcp.tool-search-auto-threshold:8}")
    private Integer mcpToolSearchAutoThreshold;

    public void setMcpToolSearchAutoThreshold(Integer mcpToolSearchAutoThreshold) {
        this.mcpToolSearchAutoThreshold = mcpToolSearchAutoThreshold;
    }

    /**
     * 后台子 Agent 结算时父 SSE 流的传输层收口策略（业务态 root result 恒 finish 已无条件解绑）。
     * true（默认）：后台在飞则投影不 complete、父流保持打开直到 stream_settle，
     * 结算 tool_result 经活连接推送，父卡秒级刷新（对齐 ZCode delta 推送 UX）；
     * false：root result 即 complete 关流，父卡改由前端冷路径（replay 重拉）刷新。
     */
    private boolean deferBackgroundSettle = true;

    /**
     * {@code @Value} 必须挂在 setter 上：挂在字段上时 Spring 直接反射写字段，本方法不会执行，
     * 静态策略 {@link StreamSettlePolicy} 永远停在默认值，配置项静默失效。
     */
    @Value("${autobots.autoagent.stream.defer-background-settle:true}")
    public void setDeferBackgroundSettle(boolean deferBackgroundSettle) {
        this.deferBackgroundSettle = deferBackgroundSettle;
        StreamSettlePolicy.setDeferBackgroundSettle(deferBackgroundSettle);
    }

    /**
     * LLM Settings
     */
    /** 调用方必须只读；该 Map 由配置绑定整体替换，直接修改会绕过配置刷新语义。 */
    private Map<String, LLMSettings> llmSettingsMap;
    @Value("${llm.settings:{}}")
    public void setLLMSettingsMap(String jsonStr) {
        Map<String, LLMSettings> rawSettings = JSON.parseObject(jsonStr, new TypeReference<Map<String, LLMSettings>>() {
        });
        this.llmSettingsMap = normalizeLlmSettingsMap(rawSettings);
    }

    @Value("${autobots.autoagent.planner.max_steps:400}")
    private Integer plannerMaxSteps;

    @Value("${autobots.autoagent.executor.max_steps:400}")
    private Integer executorMaxSteps;

    @Value("${autobots.autoagent.react.max_steps:400}")
    private Integer reactMaxSteps;

    @Value("${autobots.autoagent.executor.max_observe:10000}")
    private String maxObserve;

    /**
     * 子智能体独立会话流开关（BREAKING）。
     * <p>
     * {@code true}：子 Agent 的思考 / 工具 / 过程回复不再混入父 SSE，改为写入子会话自己的投影流，
     * 父流只保留 {@code subagent_start} / {@code subagent_end} 两条自包含帧。
     * {@code false}：维持旧的 SubAgentPrinter 透传父流行为。
     * <p>
     * 默认 false：该开关是破坏性变更，必须与右侧详情面板改造（Phase 4）同发；
     * 单独开启会让详情面板拿不到子会话轨迹。
     */
    @Value("${autobots.agent.subagent-isolated-session:false}")
    private Boolean subagentIsolatedSession;

    public boolean isSubagentIsolatedSession() {
        return subagentIsolatedSession != null && subagentIsolatedSession;
    }

    /**
     * 单次 LLM 工具调用的业务超时时间，单位秒；非正值由调用方回退到默认值。
     */
    @Value("${autobots.autoagent.llm-timeout-seconds:1200}")
    private Integer llmTimeoutSeconds;

    @Value("${autobots.autoagent.code_interpreter_url:}")
    private String codeInterpreterUrl;

    @Value("${autobots.autoagent.deep_search_url:}")
    private String deepSearchUrl;

    @Value("${autobots.autoagent.web_fetch_url:}")
    private String webFetchUrl;

    @Value("${autobots.autoagent.web_fetch_proxy:}")
    @Setter
    private String webFetchProxy;

    /**
     * LLM 出站请求代理（聊天补全与图片生成共用），支持 http/https/socks5；
     * 为空表示直连。与 web_fetch_proxy 同一配置风格，供访问境外模型端点时使用。
     */
    @Value("${autobots.autoagent.llm_proxy:}")
    private String llmProxy;

    @Value("${autobots.autoagent.multimodalagent_url:}")
    private String multiModalAgentUrl;

    @Value("${autobots.autoagent.mcp_client_url:}")
    private String mcpClientUrl;

    @Value("${autobots.autoagent.mcp_server_url:}")
    private String[] mcpServerUrlArr;

    @Value("${autobots.autoagent.knowledge_url:}")
    private String autoBotsKnowledgeUrl;

    @Value("${autobots.autoagent.data_analysis_url:}")
    private String dataAnalysisUrl;

    @Value("${autobots.autoagent.summary.system_prompt:}")
    private String summarySystemPrompt;

    @Value("${autobots.autoagent.summary.model_name:}")
    private String summaryModelName;

    @Value("${autobots.autoagent.summary.temperature:0.2}")
    private Double summaryTemperature;

    @Value("${autobots.autoagent.digital_employee_prompt:}")
    private String digitalEmployeePrompt;

    @Value("${autobots.autoagent.summary.message_size_limit:1000}")
    private Integer messageSizeLimit;

    /** 调用方必须只读；该 Map 由配置绑定整体替换，直接修改会绕过配置刷新语义。 */
    private Map<String, String> sensitivePatterns = new HashMap<>();
    @Value("${autobots.autoagent.sensitive_patterns:{}}")
    public void setSensitivePatterns(String jsonStr) {
        this.sensitivePatterns = parseStringMap(jsonStr);
    }

    /** 调用方必须只读；该 Map 由配置绑定整体替换，直接修改会绕过配置刷新语义。 */
    private Map<String, String> messageInterval = new HashMap<>();
    @Value("${autobots.autoagent.message_interval:{}}")
    public void setMessageInterval(String jsonStr) {
        this.messageInterval = parseStringMap(jsonStr);
    }

	@Value("${autobots.multiagent.sseClient.readTimeout:18000}")
	private Integer sseClientReadTimeout;

    @Value("${autobots.multiagent.sseClient.connectTimeout:18000}")
	private Integer sseClientConnectTimeout;

    @Value("${autobots.autoagent.cute_base_prompt:}")
    private String cuteBasePrompt;

    private static Map<String, String> parseStringMap(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        Map<String, String> parsed = JSON.parseObject(json, new TypeReference<Map<String, String>>() {});
        // 字面量 "null" 会解析成 null；调用方随后会 getOrDefault，必须归一为空 Map。
        return parsed == null ? new HashMap<>() : parsed;
    }

    /**
     * 对 llm.settings 的 key 和 model 字段做规范化，避免配置里混入首尾空格导致模型配置失效。
     */
    private static Map<String, LLMSettings> normalizeLlmSettingsMap(Map<String, LLMSettings> rawSettings) {
        Map<String, LLMSettings> normalizedSettings = new HashMap<>();
        if (rawSettings == null || rawSettings.isEmpty()) {
            return normalizedSettings;
        }

        rawSettings.forEach((modelName, settings) -> {
            if (settings == null) {
                log.warn("忽略 llm.settings 中配置为空的模型: {}", modelName);
                return;
            }

            String normalizedModelName = StringUtils.hasText(modelName) ? modelName.trim() : "";
            if (!StringUtils.hasText(normalizedModelName)) {
                log.warn("忽略 llm.settings 中 key 为空的模型配置");
                return;
            }
            if (normalizedSettings.containsKey(normalizedModelName)) {
                log.warn("llm.settings 存在仅空白差异的重复 key，后者覆盖前者: {}", modelName);
            }

            normalizedSettings.put(normalizedModelName, LLMSettings.builder()
                    .model(StringUtils.hasText(settings.getModel()) ? settings.getModel().trim() : normalizedModelName)
                    .maxTokens(settings.getMaxTokens())
                    .temperature(settings.getTemperature())
                    .apiType(settings.getApiType())
                    .apiKey(settings.getApiKey())
                    .apiVersion(settings.getApiVersion())
                    .baseUrl(settings.getBaseUrl())
                    .interfaceUrl(settings.getInterfaceUrl())
                    .functionCallType(settings.getFunctionCallType())
                    .maxInputTokens(settings.getMaxInputTokens())
                    .reasoningEffort(settings.getReasoningEffort())
                    .extParams(settings.getExtParams())
                    .build());
        });
        return normalizedSettings;
    }

}
