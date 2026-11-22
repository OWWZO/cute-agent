package org.wwz.ai.domain.agent.runtime.prompt;

import lombok.Builder;
import lombok.Data;

/**
 * 提示词稳定前缀的装配输入。
 *
 * <p>这里只放会话身份和会话级能力快照；日期、工作区、用户输入、历史、
 * RAG 和工具结果属于消息上下文，不进入本对象。</p>
 */
@Data
@Builder
public class PromptAssemblyContext {

    @Builder.Default
    private AgentExecutionRole role = AgentExecutionRole.MAIN;

    private String agentKey;

    /** 普通主 Agent 或 Worker 的领域职责。 */
    private String agentDirective;

    /** 协调者可见的 Agent 目录快照。 */
    private String workerCatalog;

    /** 已构建的 skill 名称/简介快照；完整 SKILL.md 不在这里。 */
    private String skillIndex;

    /** 评测专用终答契约；非评测会话为空。 */
    private String benchmarkContract;

    /** plan mode 等会话首轮确定的任务规则。 */
    private String taskInstructions;

    @Builder.Default
    private boolean includeUserFacingReplyContract = true;

    @Builder.Default
    private boolean includeMemoryGuidance = true;

    @Builder.Default
    private boolean includeWorkspaceGuidance = true;

    @Builder.Default
    private boolean includeBackgroundTaskGuidance = true;

    @Builder.Default
    private boolean includeParallelToolGuidance = true;

    @Builder.Default
    private boolean includeAgentDispatchGuidance = false;

    @Builder.Default
    private boolean includeOpenUiProtocol = false;

    public boolean isCoordinator() {
        return role == AgentExecutionRole.COORDINATOR;
    }
}
