package org.wwz.ai.application.agent.review;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ContextEventView;
import org.wwz.ai.domain.agent.ledger.model.ConversationMessageView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.MemoryEventView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 将 Execution Ledger 的只读明细转换为评测侧车可消费的 Trace Bundle。
 */
@Service
public class TaskReviewTraceExportService {

    public JSONObject buildBundle(List<ExecutionRunDetail> details) {
        JSONObject bundle = new JSONObject();
        bundle.put("schemaVersion", "1.0");
        bundle.put("exportedAt", LocalDateTime.now().toString());
        bundle.put("conversationSource", "execution-ledger");

        JSONArray runs = new JSONArray();
        JSONArray artifacts = new JSONArray();
        for (ExecutionRunDetail detail : details) {
            JSONObject trace = TaskReviewTraceSanitizer.sanitize(toTrace(detail));
            JSONObject run = new JSONObject();
            run.put("requestId", detail.getRun().getRequestId());
            run.put("sessionId", detail.getRun().getSessionId());
            run.put("trace", trace);
            runs.add(run);
            JSONArray traceArtifacts = trace.getJSONArray("artifacts");
            if (traceArtifacts != null) {
                artifacts.addAll(traceArtifacts);
            }
        }
        bundle.put("runs", runs);
        bundle.put("artifacts", artifacts);
        return bundle;
    }

    static JSONObject toTrace(ExecutionRunDetail detail) {
        JSONObject trace = new JSONObject();
        trace.put("schemaVersion", "1.1");

        JSONObject producer = new JSONObject();
        producer.put("agent", "cute-agent");
        producer.put("runtimeVersion", "task-review");
        producer.put("exportedAt", LocalDateTime.now().toString());
        trace.put("producer", producer);

        JSONObject run = new JSONObject();
        run.put("runId", detail.getRun().getId());
        run.put("runUid", detail.getRun().getRunUid());
        run.put("requestId", detail.getRun().getRequestId());
        run.put("sessionId", detail.getRun().getSessionId());
        run.put("userIdHash", hashUserId(detail.getRun().getUserId()));
        run.put("entryAgent", detail.getRun().getEntryAgent());
        run.put("status", statusName(detail.getRun().getStatus()));
        run.put("queryText", detail.getRun().getQueryText());
        run.put("finalSummaryText", detail.getRun().getFinalSummaryText());
        run.put("startedAt", time(detail.getRun().getStartedAt()));
        run.put("finishedAt", time(detail.getRun().getFinishedAt()));
        run.put("createdAt", time(detail.getRun().getCreateTime()));
        run.put("durationMs", detail.getRun().getDurationMs());
        run.put("promptTokens", detail.getRun().getPromptTokensTotal());
        run.put("completionTokens", detail.getRun().getCompletionTokensTotal());
        run.put("totalTokens", detail.getRun().getTotalTokensTotal());
        run.put("llmCallCount", detail.getRun().getLlmCallCount());
        run.put("toolCallCount", detail.getRun().getToolCallCount());
        run.put("artifactCount", detail.getRun().getArtifactCount());
        run.put("errorCode", detail.getRun().getErrorCode());
        run.put("errorMsg", detail.getRun().getErrorMsg());
        trace.put("run", run);

        JSONArray conversation = new JSONArray();
        for (ConversationMessageView item : conversationForRun(detail)) {
            JSONObject message = new JSONObject();
            message.put("messageId", item.getMessageId());
            message.put("conversationId", item.getConversationId());
            message.put("turnId", item.getTurnId());
            message.put("turnIndex", item.getTurnIndex());
            message.put("requestId", item.getRequestId());
            message.put("runId", item.getRunId());
            message.put("seqNo", item.getSeqNo());
            message.put("role", item.getRole());
            message.put("actor", item.getActor());
            message.put("content", item.getContent());
            message.put("toolCallId", item.getToolCallId());
            message.put("toolCalls", parseToolCalls(item.getToolCallsJson()));
            message.put("messageKind", item.getMessageKind());
            message.put("visibility", item.getVisibility());
            message.put("tokenEstimate", item.getTokenEstimate());
            message.put("timestamp", time(item.getTimestamp()));
            conversation.add(message);
        }
        trace.put("conversation", conversation);
        trace.put("conversationSource", detail.getConversationSource());
        trace.put("environmentEvents", new JSONArray());
        trace.put("environmentSource", "unavailable");
        trace.put("environmentSnapshots", new JSONArray());
        trace.put("snapshotSource", "unavailable");

        JSONArray llms = new JSONArray();
        for (LlmInvocationView item : detail.getLlmInvocations()) {
            JSONObject llm = new JSONObject();
            llm.put("id", item.getId());
            llm.put("invocationSeq", item.getInvocationSeq());
            llm.put("agentName", item.getAgentName());
            llm.put("stepNo", item.getStepNo());
            llm.put("callKind", item.getCallKind());
            llm.put("streaming", item.getStreaming());
            llm.put("modelName", item.getModelName());
            llm.put("responseText", item.getResponseText());
            llm.put("reasoningContent", item.getReasoningContent());
            llm.put("toolCallCount", item.getToolCallCount());
            llm.put("promptTokens", item.getPromptTokens());
            llm.put("completionTokens", item.getCompletionTokens());
            llm.put("totalTokens", item.getTotalTokens());
            llm.put("cachedPromptTokens", item.getCachedPromptTokens());
            llm.put("promptTextTokens", item.getPromptTextTokens());
            llm.put("promptAudioTokens", item.getPromptAudioTokens());
            llm.put("promptImageTokens", item.getPromptImageTokens());
            llm.put("completionTextTokens", item.getCompletionTextTokens());
            llm.put("completionAudioTokens", item.getCompletionAudioTokens());
            llm.put("reasoningTokens", item.getReasoningTokens());
            llm.put("systemFingerprint", item.getSystemFingerprint());
            llm.put("estTotalTokens", item.getEstTotalTokens());
            llm.put("estSystemTokens", item.getEstSystemTokens());
            llm.put("estMessageTokens", item.getEstMessageTokens());
            llm.put("estToolTokens", item.getEstToolTokens());
            llm.put("messageCount", item.getMessageCount());
            llm.put("toolCount", item.getToolCount());
            llm.put("cacheStatus", item.getCacheStatus());
            llm.put("cacheRiskFlags", item.getCacheRiskFlags());
            llm.put("finishReason", item.getFinishReason());
            llm.put("status", statusName(item.getStatus()));
            llm.put("errorMsg", item.getErrorMsg());
            llm.put("startedAt", time(item.getStartedAt()));
            llm.put("firstTokenAt", time(item.getFirstTokenAt()));
            llm.put("finishedAt", time(item.getFinishedAt()));
            llm.put("durationMs", item.getDurationMs());
            llms.add(llm);
        }
        trace.put("llmInvocations", llms);

        JSONArray toolCalls = new JSONArray();
        for (ToolInvocationView item : detail.getToolInvocations()) {
            String resultText = item.resolveResultText();
            JSONObject tool = new JSONObject();
            tool.put("id", item.getId());
            tool.put("llmInvocationId", item.getLlmInvocationId());
            tool.put("toolCallId", item.getToolCallId());
            tool.put("parentToolCallId", item.getParentToolCallId());
            tool.put("subAgentId", item.getSubAgentId());
            tool.put("subAgentType", item.getSubAgentType());
            tool.put("subAgentDescription", item.getSubAgentDescription());
            tool.put("dispatchIndex", item.getDispatchIndex());
            tool.put("agentName", item.getAgentName());
            tool.put("stepNo", item.getStepNo());
            tool.put("toolName", item.getToolName());
            tool.put("toolProvider", item.getToolProvider());
            tool.put("inputJson", item.getInputJson());
            tool.put("llmObservation", item.getLlmObservation());
            tool.put("toolResult", resultText);
            tool.put("status", statusName(item.getStatus()));
            tool.put("statusSource", "execution-ledger");
            tool.put("errorMsg", item.getErrorMsg());
            tool.put("errorCode", null);
            tool.put("requestedAt", time(item.getCreateTime()));
            tool.put("startedAt", time(item.getStartedAt()));
            tool.put("finishedAt", time(item.getFinishedAt()));
            tool.put("durationMs", item.getDurationMs());
            tool.put("httpStatus", null);
            tool.put("timeoutMs", null);
            tool.put("cancelledBy", null);
            tool.put("retryCount", null);
            tool.put("inputParseError", inputParseError(item.getInputJson()));
            tool.put("inputValidationErrors", new JSONArray());
            tool.put(
                    "resultPresent",
                    resultText != null && !resultText.isBlank()
            );
            tool.put("artifactCount", item.getArtifactCount());
            toolCalls.add(tool);
        }
        trace.put("toolInvocations", toolCalls);
        trace.put("toolContracts", new JSONArray());
        trace.put("toolContractSource", "unavailable");

        JSONArray contextEvents = new JSONArray();
        for (ContextEventView item : detail.getContextEvents()) {
            JSONObject event = new JSONObject();
            event.put("eventId", item.getEventId());
            event.put("type", item.getType());
            event.put("sessionId", item.getSessionId());
            event.put("triggerRequestId", item.getTriggerRequestId());
            event.put("compactRequestId", item.getCompactRequestId());
            event.put("status", item.getStatus());
            event.put("strategy", item.getStrategy());
            event.put("beforeTokens", item.getBeforeTokens());
            event.put("afterTokens", item.getAfterTokens());
            event.put("beforeMessageCount", item.getBeforeMessageCount());
            event.put("afterMessageCount", item.getAfterMessageCount());
            event.put("thresholdTokens", item.getThresholdTokens());
            event.put("summaryHash", item.getSummaryHash());
            event.put("errorCode", item.getErrorCode());
            event.put("startedAt", time(item.getStartedAt()));
            event.put("finishedAt", time(item.getFinishedAt()));
            contextEvents.add(event);
        }
        trace.put("contextEvents", contextEvents);

        JSONArray memoryEvents = new JSONArray();
        for (MemoryEventView item : detail.getMemoryEvents()) {
            JSONObject event = new JSONObject();
            event.put("eventId", item.getEventId());
            event.put("type", item.getType());
            event.put("sessionId", item.getSessionId());
            event.put("triggerRequestId", item.getTriggerRequestId());
            event.put("forkRequestId", item.getForkRequestId());
            event.put("forkKind", item.getForkKind());
            event.put("status", item.getStatus());
            event.put("skipReason", item.getSkipReason());
            event.put("ownerType", item.getOwnerType());
            event.put("ownerHash", item.getOwnerHash());
            event.put("userTurns", item.getUserTurns());
            event.put("snapshotMessageCount", item.getSnapshotMessageCount());
            event.put("maxSteps", item.getMaxSteps());
            event.put("timeoutSeconds", item.getTimeoutSeconds());
            event.put("durationMs", item.getDurationMs());
            event.put("entriesBefore", item.getEntriesBefore());
            event.put("entriesAfter", item.getEntriesAfter());
            event.put("appliedCount", item.getAppliedCount());
            event.put("errorCode", item.getErrorCode());
            event.put("startedAt", time(item.getStartedAt()));
            event.put("finishedAt", time(item.getFinishedAt()));
            memoryEvents.add(event);
        }
        trace.put("memoryEvents", memoryEvents);

        JSONArray artifacts = new JSONArray();
        for (ArtifactView item : detail.getArtifacts()) {
            JSONObject artifact = new JSONObject();
            artifact.put("id", item.getId());
            artifact.put("toolInvocationId", item.getToolInvocationId());
            artifact.put("toolCallId", item.getToolCallId());
            artifact.put("artifactRole", item.getArtifactRole());
            artifact.put("visibility", item.getVisibility());
            artifact.put("sourceType", item.getSourceType());
            artifact.put("sourceName", item.getSourceName());
            artifact.put("fileName", item.getFileName());
            artifact.put("storageKey", item.getStorageKey());
            artifact.put("downloadUrl", item.getDownloadUrl());
            artifact.put("previewUrl", item.getPreviewUrl());
            artifact.put("mimeType", item.getMimeType());
            artifact.put("fileSize", item.getFileSize());
            artifact.put("fileHash", item.getFileHash());
            artifact.put("metadataJson", item.getMetadataJson());
            artifact.put("previewAvailable", false);
            artifact.put("workspacePresent", null);
            artifact.put("contentPreview", null);
            artifacts.add(artifact);
        }
        trace.put("artifacts", artifacts);

        JSONObject environment = new JSONObject();
        environment.put("profile", "task-review");
        environment.put("mode", "read-only");
        environment.put("source", "execution-ledger");
        trace.put("environment", environment);
        return trace;
    }

    private static List<ConversationMessageView> conversationForRun(ExecutionRunDetail detail) {
        List<ConversationMessageView> conversation = detail.getConversation();
        if (conversation.isEmpty() || detail.getRun() == null) {
            return conversation;
        }
        String requestId = detail.getRun().getRequestId();
        Long runId = detail.getRun().getId();
        List<ConversationMessageView> scoped = new ArrayList<>();
        for (ConversationMessageView item : conversation) {
            String messageRequestId = item.getRequestId();
            if (requestId != null && !requestId.isBlank() && requestId.equals(messageRequestId)) {
                scoped.add(item);
                continue;
            }
            if ((messageRequestId == null || messageRequestId.isBlank())
                    && runId != null
                    && runId.equals(item.getRunId())) {
                scoped.add(item);
            }
        }
        // 兼容旧账本：没有 requestId/runId 锚点时才退回完整会话。
        return scoped.isEmpty() ? conversation : scoped;
    }

    private static String hashUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Object parseToolCalls(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return JSON.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String inputParseError(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JSON.parse(value);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    static String statusName(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case ExecutionLedgerConstants.STATUS_RUNNING -> "RUNNING";
            case ExecutionLedgerConstants.STATUS_SUCCESS -> "SUCCESS";
            case ExecutionLedgerConstants.STATUS_FAILED -> "FAILED";
            case ExecutionLedgerConstants.STATUS_TIMEOUT -> "TIMEOUT";
            case ExecutionLedgerConstants.STATUS_STOPPED -> "STOPPED";
            case ExecutionLedgerConstants.STATUS_WAITING_INPUT -> "WAITING_INPUT";
            default -> "UNKNOWN";
        };
    }

    private static String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
