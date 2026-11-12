package org.wwz.ai.infrastructure.dataquery.vector;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Qdrant endpoint and authentication settings.
 *
 * <p>{@code url} 是完整 REST 地址，非空时优先于 {@code host}；{@code port} 仍需配置，
 * 并在 URL 未显式携带端口时作为回退值。仅当 {@code url} 为空时才使用 {@code host}/{@code port}
 * 组成 gRPC/REST 端点。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "autobots.data-agent.qdrant-config")
public class QdrantProperties {
    private Boolean enable;

    /** 完整 Qdrant URL；非空时覆盖 host，port 仍需配置且可作为 URL 的默认端口。 */
    private String url;

    /** host/port 回退端点，仅在 url 为空时使用。 */
    private String host;

    private Integer port;
    @ToString.Exclude
    private String apiKey;
    private String embeddingUrl;
    private Boolean preferGrpc;
}
