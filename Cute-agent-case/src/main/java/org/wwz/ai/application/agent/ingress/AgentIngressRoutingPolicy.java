package org.wwz.ai.application.agent.ingress;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;

/**
 * 入站命令的纯校验与路由判断，不依赖 Spring 或持久化。
 */
public final class AgentIngressRoutingPolicy {

    private AgentIngressRoutingPolicy() {
    }

    public static void validate(AgentIngressCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("入站命令不能为空");
        }
        if (command.getSource() == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (command.getType() == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (command.getProtocolVersion() != null
                && command.getProtocolVersion() != AgentIngressProtocol.VERSION_1) {
            throw new IllegalArgumentException("不支持的 Agent 入站协议版本");
        }
        if (command.getType() == AgentIngressType.NEW_TURN
                && command.getProtocolVersion() != null
                && StringUtils.isBlank(command.getRequestId())) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (command.getType() == AgentIngressType.USER_GUIDANCE) {
            if (StringUtils.isAnyBlank(command.getSessionId(), command.getRequestId(), command.getQuery())) {
                throw new IllegalArgumentException("sessionId、requestId 和 text 不能为空");
            }
        }
    }

    public static boolean isNewProtocol(AgentIngressCommand command) {
        return command.getProtocolVersion() != null;
    }

    public static boolean canConvertConcurrent(AgentIngressCommand command,
                                               AgentConcurrentRunException exception) {
        return isNewProtocol(command)
                && StringUtils.isNotBlank(exception.getActiveSessionId())
                && StringUtils.equals(exception.getActiveSessionId(), command.getSessionId());
    }
}
