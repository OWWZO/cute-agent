package org.wwz.ai.domain.agent.runtime.inbox;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.tasklist.TaskNotification;

/**
 * Inbox payload 编解码。用户文本与后台通知的展示语义不同，但共用 JSON 载体。
 */
@Slf4j
public final class SessionInboxPayloads {

    private SessionInboxPayloads() {
    }

    public static String userMessage(String text) {
        JSONObject payload = new JSONObject();
        payload.put("text", text);
        return payload.toJSONString();
    }

    /**
     * @return 用户文本；payload 为空或损坏时返回 {@code null}，调用方应将其视为可丢弃的坏消息。
     */
    public static String userText(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return JSON.parseObject(payloadJson).getString("text");
        } catch (Exception e) {
            log.warn("session inbox user payload decode failed type={} payloadChars={}",
                    e.getClass().getSimpleName(), payloadJson.length());
            return null;
        }
    }

    public static String backgroundNotification(TaskNotification notification) {
        if (notification == null) {
            return null;
        }
        JSONObject payload = new JSONObject();
        payload.put("taskId", notification.taskId());
        payload.put("agentId", notification.agentId());
        payload.put("childSessionId", notification.childSessionId());
        payload.put("agentType", notification.agentType());
        payload.put("description", notification.description());
        payload.put("status", notification.status());
        payload.put("result", notification.result());
        payload.put("errorMsg", notification.errorMsg());
        payload.put("totalToolUseCount", notification.totalToolUseCount());
        payload.put("totalDurationMs", notification.totalDurationMs());
        payload.put("parentToolUseId", notification.parentToolUseId());
        payload.put("createdAt", notification.createdAt());
        return payload.toJSONString();
    }

    /**
     * @return 后台通知；payload 为空或损坏时返回 {@code null}，调用方应将其视为可丢弃的坏消息。
     */
    public static TaskNotification backgroundNotification(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            JSONObject payload = JSON.parseObject(payloadJson);
            return new TaskNotification(
                    payload.getString("taskId"),
                    payload.getString("agentId"),
                    payload.getString("childSessionId"),
                    payload.getString("agentType"),
                    payload.getString("description"),
                    payload.getString("status"),
                    payload.getString("result"),
                    payload.getString("errorMsg"),
                    payload.getInteger("totalToolUseCount"),
                    payload.getLong("totalDurationMs"),
                    payload.getString("parentToolUseId"),
                    payload.getLongValue("createdAt"));
        } catch (Exception e) {
            log.warn("session inbox notification payload decode failed type={} payloadChars={}",
                    e.getClass().getSimpleName(), payloadJson.length());
            return null;
        }
    }
}
