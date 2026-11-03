package org.wwz.ai.domain.agent.runtime.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 子 Agent 定义管理视图（含 status / displayName，装配配置非 ledger）。
 */
@Data
@Builder
public class SubAgentDefinitionRecord {

    private String agentKey;

    private String displayName;

    private String whenToUse;

    private String systemPrompt;

    @Builder.Default
    private Set<String> allowedTools = Set.of();

    @Builder.Default
    private Set<String> disallowedTools = Set.of();

    private String toolPolicyMode;

    @Builder.Default
    private Set<String> deferredTools = Set.of();

    private Integer maxSteps;

    /** 1=启用,0=禁用 */
    private Integer status;
}
