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

    /** status=0：禁用。 */
    public static final int STATUS_DISABLED = 0;

    /** status=1：启用。 */
    public static final int STATUS_ENABLED = 1;

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

    /** 1=启用,0=禁用；构造时归一，null 与非 0 值一律视为启用。 */
    @Builder.Default
    private Integer status = STATUS_ENABLED;

    SubAgentDefinitionRecord(String agentKey,
                             String displayName,
                             String whenToUse,
                             String systemPrompt,
                             Set<String> allowedTools,
                             Set<String> disallowedTools,
                             String toolPolicyMode,
                             Set<String> deferredTools,
                             Integer maxSteps,
                             Integer status) {
        this.agentKey = agentKey;
        this.displayName = displayName;
        this.whenToUse = whenToUse;
        this.systemPrompt = systemPrompt;
        this.allowedTools = allowedTools;
        this.disallowedTools = disallowedTools;
        this.toolPolicyMode = toolPolicyMode;
        this.deferredTools = deferredTools;
        this.maxSteps = maxSteps;
        this.status = normalizeStatus(status);
    }

    /** 状态归一：保证消费侧只会看到 {@link #STATUS_ENABLED} / {@link #STATUS_DISABLED}。 */
    private static int normalizeStatus(Integer status) {
        return status != null && status == STATUS_DISABLED ? STATUS_DISABLED : STATUS_ENABLED;
    }

    public void setStatus(Integer status) {
        this.status = normalizeStatus(status);
    }
}
