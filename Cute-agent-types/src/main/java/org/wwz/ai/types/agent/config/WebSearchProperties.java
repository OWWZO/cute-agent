package org.wwz.ai.types.agent.config;

import lombok.Builder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSearch 四家搜索提供方配置（{@code autobots.autoagent.web_search.*}）。
 *
 * <p>从 {@code CuteConfig} 拆出：这 13 个字段只有 {@code WebSearchTool} 一个消费方，
 * 与 Agent 执行编排无耦合，混在共享配置里纯粹放大 CuteConfig 的面。</p>
 *
 * <p>放在 types 层而非 app：{@code WebSearchTool} 是 domain 的 BaseTool，
 * 只能从 {@code AgentContext} 拿配置，因此配置对象必须能被
 * {@code CuteRuntimeDependencies}（domain）引用——与 {@link CodeModeProperties}、
 * {@link HostCliProperties} 同一归属。</p>
 *
 * <p>字段默认值与拆出前 CuteConfig 上同名 {@code @Value} 冒号后的值逐一相同。</p>
 */
@Data
@Builder
@ConfigurationProperties(prefix = "autobots.autoagent.web_search")
public class WebSearchProperties {

    /**
     * WebSearch 模式：auto | gpt | grok | exa | tavily | brave | disabled。
     * auto 时优先 Grok/xAI 原生搜索，其次 GPT/OpenAI Responses API、Exa、Tavily、Brave。
     */
    private String mode = "auto";

    /** Grok/xAI 原生搜索凭证；为空时回退 llm.default / llm.settings。 */
    private String grokApiKey = "";

    private String grokBaseUrl = "";

    private String grokModel = "";

    private String grokInterfaceUrl = "/v1/chat/completions";

    /** OpenAI Responses API 搜索凭证；支持官方 OpenAI 或兼容 Responses API 的代理。 */
    private String gptApiKey = "";

    private String gptBaseUrl = "https://api.openai.com/v1";

    private String gptModel = "gpt-4.1";

    private String gptInterfaceUrl = "/responses";

    private String exaApiKey = "";

    private String exaSearchUrl = "https://api.exa.ai/search";

    private String tavilyApiKey = "";

    private String braveApiKey = "";
}
