package org.wwz.ai.domain.agent.runtime.askuser;

import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationSerializer;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.yield.PendingYieldToolCalls;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AskUserQuestion observation 与客户端卡片载荷的统一构造。
 */
public final class AskUserQuestionObservationSupport {

    private AskUserQuestionObservationSupport() {
    }

    public static String buildWaitingObservation(List<Map<String, Object>> questions, String questionId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message", "Waiting for user to answer AskUserQuestion.");
        fields.put("status", "waiting_user_input");
        fields.put("questions", questions == null ? List.of() : questions);
        fields.put("questionId", questionId);
        ToolResultPayload payload = ToolResultPayload.okData(AskUserQuestionTool.NAME, fields);
        return ToolObservationSerializer.serializeSuccess(payload.getLlmData());
    }

    public static String buildAnswerObservation(List<Map<String, Object>> questions,
                                               Map<String, String> answers,
                                               String questionId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message", "User has answered your questions. Continue with the answers in mind.");
        fields.put("questions", questions == null ? List.of() : questions);
        fields.put("answers", answers == null ? Map.of() : answers);
        fields.put("questionId", questionId);
        ToolResultPayload payload = ToolResultPayload.okData(AskUserQuestionTool.NAME, fields);
        return ToolObservationSerializer.serializeSuccess(payload.getLlmData());
    }

    /**
     * 从后往前找未配对（或仅有 waiting 占位）的 AskUserQuestion toolCallId。
     */
    public static String resolveAskUserToolCallId(List<Message> messages, String preferredToolCallId) {
        return PendingYieldToolCalls.resolve(messages, preferredToolCallId,
                AskUserQuestionTool.NAME, false);
    }

    public static boolean hasToolResult(List<Message> messages, String toolCallId) {
        return PendingYieldToolCalls.hasToolResult(messages, toolCallId);
    }

    public static Map<String, Object> toClientPayload(UserQuestionRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageType", "ask_user_question");
        if (record == null) {
            return map;
        }
        map.put("questionId", record.getQuestionId());
        map.put("sessionId", record.getSessionId());
        map.put("requestId", record.getSourceRequestId());
        map.put("toolCallId", record.getToolCallId());
        map.put("status", toClientStatus(record.getStatus()));
        map.put("persistenceStatus", record.getStatus());
        map.put("questions", record.getQuestions());
        if (record.getAnswers() != null) {
            map.put("answers", record.getAnswers());
        }
        if (record.getExpiresAt() != null) {
            map.put("expiresAt", record.getExpiresAt().toString());
        }
        map.put("resumeRequestId", record.getResumeRequestId());
        return map;
    }

    /**
     * 持久化状态 → 客户端卡片状态。
     * <p>客户端只区分「还在等」与「已结束」：只有 {@code PENDING} 映射为 {@code pending}；
     * 续跑中（{@code RESUMING}/{@code RESUME_PENDING}）与已答（{@code ANSWERED}）都是
     * {@code answered}；超时为 {@code timeout}；用户取消与系统失败各自独立
     * （{@code cancelled} / {@code failed}），不再把失败伪装成用户取消。
     * 未知/空状态不当作开放问题，返回 {@code unknown}，避免陈旧记录渲染成可回答的卡片。
     * 原始状态另见 {@code persistenceStatus}。</p>
     */
    public static String toClientStatus(String status) {
        if (UserQuestionStatuses.ANSWERED.equals(status) || UserQuestionStatuses.RESUMING.equals(status)
                || UserQuestionStatuses.RESUME_PENDING.equals(status)) {
            return "answered";
        }
        if (UserQuestionStatuses.TIMEOUT.equals(status)) {
            return "timeout";
        }
        if (UserQuestionStatuses.CANCELLED.equals(status)) {
            return "cancelled";
        }
        if (UserQuestionStatuses.FAILED.equals(status)) {
            return "failed";
        }
        if (UserQuestionStatuses.PENDING.equals(status)) {
            return "pending";
        }
        return "unknown";
    }
}
