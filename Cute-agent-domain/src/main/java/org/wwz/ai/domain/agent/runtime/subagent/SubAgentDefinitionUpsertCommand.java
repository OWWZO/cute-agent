package org.wwz.ai.domain.agent.runtime.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 子 Agent 定义创建/更新命令。
 * <p>工具集合默认为空 {@link Set}（null 视为未设置，构造时归一为空集），
 * 下游不必区分 null 与空集。</p>
 */
@Data
@Builder
public class SubAgentDefinitionUpsertCommand {

    private String agentKey;

    private String displayName;

    private String whenToUse;

    private String systemPrompt;

    @lombok.Builder.Default
    private Set<String> allowedTools = Set.of();

    @lombok.Builder.Default
    private Set<String> disallowedTools = Set.of();

    private String toolPolicyMode;

    @lombok.Builder.Default
    private Set<String> deferredTools = Set.of();

    private Integer maxSteps;

    /** 1=启用,0=禁用；null 时 create 默认 1 */
    private Integer status;

    /** 返回不可变副本，避免调用方在命令交给仓储后继续修改工具策略。 */
    public Set<String> getAllowedTools() {
        return allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    }

    public Set<String> getDisallowedTools() {
        return disallowedTools == null ? Set.of() : Set.copyOf(disallowedTools);
    }

    public Set<String> getDeferredTools() {
        return deferredTools == null ? Set.of() : Set.copyOf(deferredTools);
    }
}
