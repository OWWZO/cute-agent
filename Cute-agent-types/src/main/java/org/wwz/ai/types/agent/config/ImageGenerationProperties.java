package org.wwz.ai.types.agent.config;

import lombok.Builder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片生成上游配置（{@code autobots.autoagent.image_generation.*}）。
 *
 * <p>从 {@code CuteConfig} 拆出：这 7 个字段只有 {@code CuteImageGenerationGateway} 一个
 * 消费方。{@code url} 是兼容旧配置的历史键，保留在同一对象里，语义仍是 base_url 兜底。</p>
 *
 * <p>放在 types 层而非 app：与 {@link WebSearchProperties} 同理——消费方在
 * infrastructure，但要经 {@code CuteRuntimeDependencies}（domain）传递。</p>
 *
 * <p>字段默认值与拆出前 CuteConfig 上同名 {@code @Value} 冒号后的值逐一相同。</p>
 */
@Data
@Builder
@ConfigurationProperties(prefix = "autobots.autoagent.image_generation")
public class ImageGenerationProperties {

    /** 图片上游 base URL（OpenAI 兼容），由部署环境配置。 */
    private String baseUrl = "";

    private String apiKey = "";

    private String model = "gpt-image-2.5-flare";

    private String grokBaseUrl = "";

    private String grokApiKey = "";

    private String grokModel = "grok-imagine-image-lite";

    /**
     * 兼容旧配置：历史上指向 cute-tool 的 image_generation 代理地址。
     * Java 直连上游后不再作为主配置，仅作 base_url 兜底。
     */
    private String url = "";
}
