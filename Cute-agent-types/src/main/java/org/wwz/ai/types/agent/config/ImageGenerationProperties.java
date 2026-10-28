package org.wwz.ai.types.agent.config;

import lombok.Builder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片生成上游配置（{@code autobots.autoagent.image-generation.*}）。
 *
 * <p>从 {@code CuteConfig} 拆出：这 7 个字段只有 {@code CuteImageGenerationGateway} 一个
 * 消费方。{@code url} 是兼容旧配置的历史键，保留在同一对象里，语义仍是 base_url 兜底。</p>
 *
 * <p>放在 types 层而非 app：与 {@link WebSearchProperties} 同理——消费方在
 * infrastructure，但要经 {@code CuteRuntimeDependencies}（domain）传递。</p>
 *
 * <p>字段默认值与拆出前 CuteConfig 上同名 {@code @Value} 冒号后的值逐一相同。</p>
 *
 * <p><b>注意 prefix 与 yml 键名的关系</b>：{@code @ConfigurationProperties} 的 prefix 本身
 * 必须是 canonical kebab-case，写 {@code image_generation} 会启动报
 * {@code Invalid characters: '_'}。yml 里的键名仍写 {@code image_generation}——
 * relaxed binding 会把 {@code _} 归一化成 {@code -}，两者等价，无需改配置。</p>
 */
@Data
@Builder
@ConfigurationProperties(prefix = "autobots.autoagent.image-generation")
public class ImageGenerationProperties {

    /** 图片上游 base URL（OpenAI 兼容），由部署环境配置。 */
    @Builder.Default
    private String baseUrl = "";

    @Builder.Default
    private String apiKey = "";

    @Builder.Default
    private String model = "gpt-image-2.5-flare";

    @Builder.Default
    private String grokBaseUrl = "";

    @Builder.Default
    private String grokApiKey = "";

    @Builder.Default
    private String grokModel = "grok-imagine-image-lite";

    /**
     * 兼容旧配置：历史上指向 cute-tool 的 image_generation 代理地址。
     * Java 直连上游后不再作为主配置，仅作 base_url 兜底。
     *
     * <p><b>已知行为差异</b>：从 CuteConfig 拆出前，该字段绑的是同级键
     * {@code autobots.autoagent.image_generation_url}；拆出后按 prefix 归一化绑到
     * {@code image-generation.url}，而 dev/prod 都没有这个键，因此它恒为空。
     * 由于三个 profile 里 {@code image_generation_url} 的值本身就是 {@code ""}，
     * 兜底结果与改造前一致，暂无实际影响。若将来要恢复读取旧键，需改用
     * {@code @ConfigurationProperty(name = "image_generation_url")} 显式指定路径
     * （该注解只能写在构造器参数或 record 组件上，不能写在字段上）。</p>
     */
    @Builder.Default
    private String url = "";
}
