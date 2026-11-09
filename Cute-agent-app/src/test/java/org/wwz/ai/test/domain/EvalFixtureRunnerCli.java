package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ContextEventView;
import org.wwz.ai.domain.agent.ledger.model.ConversationMessageView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.MemoryEventView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ReActAgent;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 第一阶段测试专用评测入口。
 *
 * <p>不启动生产 HTTP，也不改写生产工具。固定模型输出由 case 脚本提供，
 * Agent 控制循环、工具管线、Ledger 和 Trace 仍然走真实 Java 实现。</p>
 */
public final class EvalFixtureRunnerCli {

    private EvalFixtureRunnerCli() {
    }

    public static void main(String[] args) throws Exception {
        Path casesFile = null;
        Path outDir = null;
        for (int i = 0; i < args.length; i++) {
            if ("--cases".equals(args[i]) && i + 1 < args.length) {
                casesFile = Path.of(args[++i]);
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                outDir = Path.of(args[++i]);
            }
        }
        if (casesFile == null || outDir == null) {
            System.err.println("usage: EvalFixtureRunnerCli --cases <cases.json> --out <dir>");
            System.exit(2);
        }

        JSONObject manifest = JSON.parseObject(Files.readString(casesFile, StandardCharsets.UTF_8));
        JSONArray cases = manifest.getJSONArray("cases");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases manifest must contain a non-empty cases array");
        }
        JSONObject environment = manifest.getJSONObject("environment");

        Files.createDirectories(outDir);
        JSONArray resultCases = new JSONArray();
        for (int i = 0; i < cases.size(); i++) {
            JSONObject evalCase = cases.getJSONObject(i);
            JSONObject result = runCase(evalCase, outDir, environment);
            resultCases.add(result);
        }

        JSONObject result = new JSONObject();
        result.put("schemaVersion", "1.0");
        result.put("runner", "cute-agent-java-fixture");
        result.put("cases", resultCases);
        Files.writeString(outDir.resolve("result.json"), result.toJSONString(), StandardCharsets.UTF_8);
    }

    private static JSONObject runCase(JSONObject evalCase,
                                      Path outDir,
                                      JSONObject environment) throws Exception {
        String caseId = required(evalCase, "id");
        String query = required(evalCase, "query");
        JSONArray script = evalCase.getJSONArray("script");
        if (script == null || script.isEmpty()) {
            throw new IllegalArgumentException("case " + caseId + " must contain a non-empty script");
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String requestId = "eval-" + caseId.replaceAll("[^A-Za-z0-9_-]", "-") + "-" + suffix;
        String sessionId = "eval-session-" + suffix;
        ExecutionLedgerFixtureFactory.LedgerTestContext ledger =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                requestId, sessionId, query, ledger.recorder);
        Long runId = ExecutionLedgerFixtureFactory.activateRun(
                context, ledger.recorder, ExecutionLedgerConstants.ENTRY_AGENT_REACT);

        Map<String, ScriptedTool> tools = buildTools(
                script, evalCase.getJSONArray("tools"), context);
        tools.values().forEach(tool -> context.getToolCollection().addTool(tool));

        FixtureReactAgent agent = new FixtureReactAgent(context, script, ledger);
        String finalText;
        int runStatus;
        try {
            finalText = agent.run(query);
            runStatus = agent.getState() == AgentState.FINISHED
                    ? ExecutionLedgerConstants.STATUS_SUCCESS
                    : ExecutionLedgerConstants.STATUS_FAILED;
        } catch (Exception e) {
            finalText = e.getMessage();
            runStatus = ExecutionLedgerConstants.STATUS_FAILED;
        }
        ledger.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .status(runStatus)
                .finalSummaryText(finalText == null ? "" : finalText)
                .errorCode(runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? null : "EVAL_FIXTURE_ERROR")
                .errorMsg(runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? null : finalText)
                .build());

        ExecutionRunDetail detail = ledger.queryService.queryRunDetail(requestId);
        if (detail == null || detail.getRun() == null) {
            throw new IllegalStateException("ledger trace not found for requestId=" + requestId);
        }
        JSONObject trace = toTrace(caseId, detail, environment);
        Path caseDir = outDir.resolve("traces").resolve(caseId);
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("trace.json"), trace.toJSONString(), StandardCharsets.UTF_8);

        JSONObject result = new JSONObject();
        result.put("caseId", caseId);
        result.put("requestId", requestId);
        result.put("sessionId", sessionId);
        result.put("status", statusName(runStatus));
        result.put("finalText", finalText);
        result.put("tracePath", "traces/" + caseId + "/trace.json");
        return result;
    }

    private static Map<String, ScriptedTool> buildTools(JSONArray script,
                                                        JSONArray toolDefinitions,
                                                        AgentContext context) {
        Map<String, ScriptedTool> tools = new LinkedHashMap<>();
        if (toolDefinitions != null) {
            for (int i = 0; i < toolDefinitions.size(); i++) {
                JSONObject definition = toolDefinitions.getJSONObject(i);
                String name = required(definition, "name");
                tools.put(name, new ScriptedTool(name, definition, context));
            }
        }
        for (int i = 0; i < script.size(); i++) {
            JSONObject action = script.getJSONObject(i);
            if (!"tool".equals(action.getString("type"))) {
                continue;
            }
            String name = required(action, "tool");
            tools.computeIfAbsent(
                    name, key -> new ScriptedTool(key, null, context)).enqueue(action);
        }
        return tools;
    }

    static JSONObject toTrace(String caseId, ExecutionRunDetail detail) {
        return toTrace(caseId, detail, null);
    }

    static JSONObject toTrace(String caseId,
                              ExecutionRunDetail detail,
                              JSONObject environment) {
        JSONObject trace = new JSONObject();
        trace.put("schemaVersion", "1.1");

        JSONObject producer = new JSONObject();
        producer.put("agent", "cute-agent");
        String runtimeVersion = EvalFixtureRunnerCli.class.getPackage().getImplementationVersion();
        producer.put("runtimeVersion", runtimeVersion == null ? "unknown" : runtimeVersion);
        producer.put("exportedAt", LocalDateTime.now().toString());
        trace.put("producer", producer);

        JSONObject run = new JSONObject();
        run.put("caseId", caseId);
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
        for (ConversationMessageView item : detail.getConversation()) {
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
            tool.put("toolResult", item.resolveResultText());
            tool.put("status", statusName(item.getStatus()));
            tool.put("errorMsg", item.getErrorMsg());
            tool.put("startedAt", time(item.getStartedAt()));
            tool.put("finishedAt", time(item.getFinishedAt()));
            tool.put("durationMs", item.getDurationMs());
            tool.put("artifactCount", item.getArtifactCount());
            toolCalls.add(tool);
        }
        trace.put("toolInvocations", toolCalls);

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
            artifacts.add(artifact);
        }
        trace.put("artifacts", artifacts);
        trace.put("environment", environment == null ? new JSONObject() : environment);
        return trace;
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

    private static String required(JSONObject object, String key) {
        String value = object.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private static final class FixtureReactAgent extends ReActAgent {
        private final JSONArray script;
        private final ExecutionLedgerFixtureFactory.LedgerTestContext ledger;
        private int actionIndex;
        private JSONObject currentAction;
        private Long currentLlmId;
        private LocalDateTime currentStartedAt;

        private FixtureReactAgent(AgentContext context,
                                  JSONArray script,
                                  ExecutionLedgerFixtureFactory.LedgerTestContext ledger) {
            setName("react");
            setContext(context);
            setAvailableTools(context.getToolCollection());
            setMaxSteps(script.size() + 1);
            this.script = script;
            this.ledger = ledger;
        }

        @Override
        public boolean think() {
            if (actionIndex >= script.size()) {
                setState(AgentState.FINISHED);
                return false;
            }
            currentAction = script.getJSONObject(actionIndex++);
            currentStartedAt = LocalDateTime.now();
            currentLlmId = ExecutionLedgerFixtureFactory.createLlmInvocation(
                    context,
                    ledger.recorder,
                    getName(),
                    getCurrentStep(),
                    ExecutionLedgerConstants.CALL_KIND_ASK_TOOL,
                    true,
                    currentStartedAt,
                    "eval-fixture-model"
            );
            String type = currentAction.getString("type");
            if ("final".equals(type)) {
                finishLlm(currentLlmId, currentStartedAt, currentAction.getString("content"), 0, currentAction);
                return true;
            }
            if (!"tool".equals(type)) {
                throw new IllegalArgumentException("unsupported script action type: " + type);
            }

            finishLlm(currentLlmId, currentStartedAt, currentAction.getString("thought"), 1, currentAction);
            return true;
        }

        @Override
        public String act() {
            if (currentAction == null) {
                setState(AgentState.FINISHED);
                return "";
            }
            String type = currentAction.getString("type");
            if ("final".equals(type)) {
                String content = currentAction.getString("content");
                setState(AgentState.FINISHED);
                getMemory().addMessage(org.wwz.ai.domain.agent.runtime.dto.Message.assistantMessage(content, null));
                return content;
            }

            String toolName = required(currentAction, "tool");
            String arguments = currentAction.getJSONObject("arguments") == null
                    ? "{}"
                    : currentAction.getJSONObject("arguments").toJSONString();
            String toolCallId = "call-" + getCurrentStep() + "-" + toolName;
            ToolCall toolCall = ExecutionLedgerFixtureFactory.newToolCall(toolCallId, toolName, arguments);
            Map<String, String> result = executeTools(List.of(toolCall));
            return result.getOrDefault(toolCallId, "");
        }

        private void finishLlm(Long llmId,
                               LocalDateTime startedAt,
                               String responseText,
                               int toolCallCount,
                               JSONObject action) {
            LocalDateTime finishedAt = LocalDateTime.now();
            LocalDateTime firstTokenAt = startedAt.plusNanos(10_000_000L);
            if (firstTokenAt.isAfter(finishedAt)) {
                firstTokenAt = finishedAt;
            }
            ledger.recorder.finishLlmInvocation(LlmInvocationFinishRecord.builder()
                    .llmInvocationId(llmId)
                    .requestId(context.getRequestId())
                    .sessionId(context.getSessionId())
                    .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                    .responseText(responseText)
                    .toolCallCount(toolCallCount)
                    .promptTokens(action.getIntValue("promptTokens") == 0 ? 100 : action.getIntValue("promptTokens"))
                    .completionTokens(action.getIntValue("completionTokens") == 0 ? 20 : action.getIntValue("completionTokens"))
                    .totalTokens(action.getIntValue("totalTokens") == 0 ? 120 : action.getIntValue("totalTokens"))
                    .finishReason(toolCallCount > 0 ? "tool_calls" : "stop")
                    .firstTokenAt(firstTokenAt)
                    .finishedAt(finishedAt)
                    .build());
        }
    }

    private static final class ScriptedTool implements BaseTool {
        private final String name;
        private final JSONObject definition;
        private final AgentContext context;
        private final Deque<JSONObject> actions = new ArrayDeque<>();

        private ScriptedTool(String name, JSONObject definition, AgentContext context) {
            this.name = name;
            this.definition = definition;
            this.context = context;
        }

        private ScriptedTool enqueue(JSONObject action) {
            actions.addLast(action);
            return this;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            String description = definition == null ? null : definition.getString("description");
            return description == null || description.isBlank()
                    ? "deterministic eval fixture tool"
                    : description;
        }

        @Override
        public Map<String, Object> toParams() {
            JSONObject official = definition == null
                    ? null
                    : definition.getJSONObject("parameters");
            if (official != null) {
                return official;
            }
            return Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string"),
                    "query", Map.of("type", "string")
            ));
        }

        @Override
        public Object execute(Object input, AgentContext runtimeContext) {
            JSONObject action = actions.pollFirst();
            if (action == null) {
                return ToolResultPayload.builder()
                        .failed(true)
                        .toolResult("EVAL_FIXTURE_EXHAUSTED")
                        .errorMsg("EVAL_FIXTURE_EXHAUSTED")
                        .build();
            }
            String fault = action.getString("fault");
            if ("timeout".equals(fault)) {
                throw new RuntimeException(new TimeoutException("tool timeout"));
            }
            if ("rate_limit".equals(fault)) {
                throw new IllegalStateException("429 rate limit");
            }
            if ("permission".equals(fault)) {
                throw new IllegalStateException("permission denied");
            }
            if ("upstream".equals(fault)) {
                return ToolResultPayload.builder()
                        .failed(true)
                        .toolResult("UPSTREAM_503")
                        .errorMsg("upstream service unavailable (503)")
                        .build();
            }
            if ("invalid_arguments".equals(fault)) {
                return ToolResultPayload.builder()
                        .failed(true)
                        .toolResult("INVALID_ARGUMENTS: path is required")
                        .errorMsg("INVALID_ARGUMENTS: path is required")
                        .build();
            }
            if ("malformed".equals(fault)) {
                return ToolResultPayload.builder()
                        .failed(true)
                        .toolResult("{not-valid-json")
                        .errorMsg("MALFORMED_JSON")
                        .build();
            }

            JSONObject artifact = action.getJSONObject("artifact");
            if (artifact != null) {
                org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource source =
                        context.requireCurrentToolArtifactSource(getName());
                context.registerGeneratedArtifact(source, File.builder()
                        .fileName(artifact.getString("fileName"))
                        .ossUrl(artifact.getString("downloadUrl"))
                        .domainUrl(artifact.getString("downloadUrl"))
                        .isInternalFile(false)
                        .build());
            }
            return action.getString("result") == null ? "" : action.getString("result");
        }
    }
}
