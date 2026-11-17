package org.wwz.ai.application.agent.ingress;

/**
 * Agent 入站命令来源。首期只开放 Web，其他值在对应适配器落地时再加入。
 */
public enum AgentIngressSource {

    WEB("web");

    private final String value;

    AgentIngressSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
