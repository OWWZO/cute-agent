package org.wwz.ai.infrastructure.dataquery.elasticsearch;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Elasticsearch connection settings owned by Infrastructure. */
@Data
@Component
@ConfigurationProperties(prefix = "autobots.data-agent.es-config")
public class ElasticsearchProperties {

    private Boolean enable;
    private String host;
    private String user;
    @ToString.Exclude
    private String password;
    @ToString.Exclude
    private String apiKey;
    private String scheme;
}
