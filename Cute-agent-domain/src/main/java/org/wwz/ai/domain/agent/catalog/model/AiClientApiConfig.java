package org.wwz.ai.domain.agent.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * API 连接配置领域模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientApiConfig {

    private Long id;
    private String apiId;
    private String baseUrl;
    /** 明文凭证，禁止进入 toString / equals / hashCode。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String apiKey;
    private String completionsPath;
    private String embeddingsPath;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
