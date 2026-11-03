package org.wwz.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 可配置子 Agent 定义 PO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentDefinitionPO {

    private Long id;

    private String agentKey;

    private String displayName;

    private String whenToUse;

    @ToString.Exclude
    private String systemPrompt;

    @ToString.Exclude
    private String allowedToolsJson;

    @ToString.Exclude
    private String disallowedToolsJson;

    private String toolPolicyMode;

    @ToString.Exclude
    private String deferredToolsJson;

    private Integer maxSteps;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
