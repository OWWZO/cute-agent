package org.wwz.ai.domain.agent.runtime.enums;

/**
 * 代理状态枚举
 */
public enum AgentState {
    IDLE,       // 空闲状态
    RUNNING,    // 运行状态
    FINISHED,   // 完成状态
    ERROR;      // 错误状态

    /** 是否已进入终态（完成 / 错误）。新增状态时只改这里。 */
    public boolean isTerminal() {
        return this == FINISHED || this == ERROR;
    }
}
