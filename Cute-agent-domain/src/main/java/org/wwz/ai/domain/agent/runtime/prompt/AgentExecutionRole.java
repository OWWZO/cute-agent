package org.wwz.ai.domain.agent.runtime.prompt;

/**
 * 一次提示词装配所服务的运行时角色。
 */
public enum AgentExecutionRole {
    MAIN,
    COORDINATOR,
    WORKER
}
