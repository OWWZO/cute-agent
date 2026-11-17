package org.wwz.ai.application.agent.ingress;

/**
 * 服务端内部入站状态。
 */
public enum AgentIngressStatus {

    NEW_RUN_ACCEPTED("new_run_accepted"),
    INJECTED("injected"),
    QUEUED("queued"),
    HELD("held"),
    DUPLICATE("duplicate"),
    INJECT_FAILED("inject_failed");

    private final String value;

    AgentIngressStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
