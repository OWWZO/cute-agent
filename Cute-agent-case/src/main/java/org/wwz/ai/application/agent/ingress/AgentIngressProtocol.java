package org.wwz.ai.application.agent.ingress;

/**
 * 新旧 Web 客户端的请求头协议。
 */
public final class AgentIngressProtocol {

    public static final String HEADER = "X-Agent-Ingress-Version";
    public static final int VERSION_1 = 1;

    private AgentIngressProtocol() {
    }
}
