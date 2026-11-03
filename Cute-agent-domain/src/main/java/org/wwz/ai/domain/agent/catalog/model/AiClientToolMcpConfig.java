package org.wwz.ai.domain.agent.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * MCP 连接配置领域模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientToolMcpConfig {

    private Long id;
    private String mcpId;
    private String mcpName;
    private String transportType;
    /** stdio env / HTTP headers 通常含 API Key、Bearer token，禁止进入 toString / equals / hashCode。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String transportConfig;
    private Integer requestTimeout;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
