package org.wwz.ai.application.agent.ingress;

/**
 * Web 新消息的具体入口，只用于路由和观测。
 */
public enum AgentIngressOrigin {

    MAIN_CHAT,
    SESSION_QUERY,
    RUN_INJECT
}
