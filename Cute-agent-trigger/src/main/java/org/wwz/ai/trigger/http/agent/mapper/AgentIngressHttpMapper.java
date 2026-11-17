package org.wwz.ai.trigger.http.agent.mapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.application.agent.ingress.AgentIngressMode;
import org.wwz.ai.application.agent.ingress.AgentIngressProtocol;
import org.wwz.ai.application.agent.ingress.AgentIngressResult;
import org.wwz.ai.application.agent.ingress.AgentIngressStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一入站结果到 HTTP JSON 的显式映射。
 */
@Component
public class AgentIngressHttpMapper {

    public Integer protocolVersion(String headerValue) {
        String value = StringUtils.trimToNull(headerValue);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("X-Agent-Ingress-Version 不是有效版本");
        }
    }

    public Map<String, Object> data(AgentIngressResult result, boolean protocolFields) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", result.isAccepted());
        putIfNotNull(data, "sessionId", result.getSessionId());
        putIfNotNull(data, "requestId", result.getRequestId());
        if (!protocolFields) {
            return data;
        }
        putIfNotNull(data, "mode", result.getMode() == null ? null : result.getMode().value());
        putIfNotNull(data, "activeRequestId", result.getActiveRequestId());
        putIfNotNull(data, "clientMessageId", result.getClientMessageId());
        data.put("retryable", result.isRetryable());
        data.put("duplicate", result.isDuplicate());
        if (result.getStatus() == AgentIngressStatus.INJECT_FAILED
                || result.getMode() == AgentIngressMode.HELD
                || result.getMode() == AgentIngressMode.QUEUED
                || result.getMode() == AgentIngressMode.INJECTED) {
            data.put("queued", result.isQueued());
            data.put("held", result.isHeld());
            data.put("wakeScheduled", result.isWakeScheduled());
            putIfNotNull(data, "message", result.getMessage());
        }
        return data;
    }

    public Map<String, Object> data(AgentIngressResult result) {
        return data(result, true);
    }

    public Map<String, Object> injectData(AgentIngressResult result) {
        Map<String, Object> data = data(result);
        data.put("accepted", result.isAccepted());
        data.put("queued", result.isQueued());
        data.put("held", result.isHeld());
        data.put("wakeScheduled", result.isWakeScheduled());
        putIfNotNull(data, "message", result.getMessage());
        return data;
    }

    public boolean isProtocolV1(Integer protocolVersion) {
        return protocolVersion != null && protocolVersion == AgentIngressProtocol.VERSION_1;
    }

    private static void putIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
