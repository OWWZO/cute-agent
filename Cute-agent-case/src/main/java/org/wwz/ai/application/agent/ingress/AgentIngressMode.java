package org.wwz.ai.application.agent.ingress;

/**
 * 对外协议中的入站结果模式。
 */
public enum AgentIngressMode {

    NEW_RUN("new_run"),
    INJECTED("injected"),
    QUEUED("queued"),
    HELD("held"),
    INJECT_FAILED("inject_failed");

    private final String value;

    AgentIngressMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
