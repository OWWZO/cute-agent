package org.wwz.ai.application.agent.ingress;

/**
 * Agent 统一入站应用服务。
 */
public interface IAgentIngressApplicationService {

    AgentIngressResult submit(AgentIngressCommand command);
}
