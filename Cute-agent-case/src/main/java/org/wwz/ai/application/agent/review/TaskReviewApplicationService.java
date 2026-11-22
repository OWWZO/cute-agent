package org.wwz.ai.application.agent.review;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelCatalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 单次任务复盘的只读查询和 Trace Bundle 编排。
 */
@Service
@RequiredArgsConstructor
public class TaskReviewApplicationService {

    static final int MAX_REQUEST_IDS = 20;
    static final int MAX_TRACE_BYTES = 20 * 1024 * 1024;
    static final int MAX_SESSION_TREE_SIZE = ExecutionLedgerQueryService.MAX_SESSION_TREE_SIZE;

    private final ConversationSessionAuthorizationService conversationSessionAuthorizationService;
    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final TaskReviewTraceExportService traceExportService;
    private final LlmModelCatalog llmModelCatalog;

    public Map<String, Object> preflight(String userId,
                                         String sessionId,
                                         List<String> requestIds) {
        ReviewLoad load = load(userId, sessionId, requestIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("requestIds", load.requestIds());
        result.put("title", load.session.getTitle());
        result.put("model", load.session.getModel());
        result.put("canReview", load.blockers().isEmpty());
        result.put("blockers", load.blockers());
        result.put("runs", load.runs().stream().map(this::runSummary).toList());
        result.put("aggregate", aggregate(load.runs()));
        return result;
    }

    public JSONObject exportBundle(String userId,
                                   String sessionId,
                                   List<String> requestIds) {
        ReviewLoad load = load(userId, sessionId, requestIds);
        if (!load.blockers().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", load.blockers()));
        }
        JSONObject bundle = traceExportService.buildBundle(load.runs());
        int bytes = bundle.toJSONString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TRACE_BYTES) {
            throw new IllegalArgumentException("Trace Bundle 超过 20MB 限制");
        }
        return bundle;
    }

    /**
     * 导出整个 Agent 会话树的 Trace Bundle。
     *
     * <p>以任一节点为入口，按 {@code rootSessionId} 一次读取根会话和全部后代，
     * 并汇总树中每个会话的已结束 Run。</p>
     */
    public JSONObject exportTreeBundle(String userId, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        conversationSessionAuthorizationService.ensureExistingSessionAccessible(userId, sessionId);
        List<DialogueSessionView> sessions = executionLedgerQueryService.querySessionTree(
                userId,
                sessionId,
                MAX_SESSION_TREE_SIZE + 1
        );
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        if (sessions.size() > MAX_SESSION_TREE_SIZE) {
            throw new IllegalArgumentException("会话树超过 " + MAX_SESSION_TREE_SIZE + " 个节点，无法复盘");
        }

        JSONObject bundle = new JSONObject();
        bundle.put("schemaVersion", "2.0");
        bundle.put("scope", "tree");
        bundle.put("rootSessionId", sessions.get(0).getRootSessionId());
        bundle.put("exportedAt", java.time.LocalDateTime.now().toString());
        bundle.put("conversationSource", "execution-ledger");

        JSONArray sessionArray = new JSONArray();
        JSONArray runArray = new JSONArray();
        JSONArray artifactArray = new JSONArray();
        List<ExecutionRunDetail> details = new ArrayList<>();
        int runCount = 0;
        for (DialogueSessionView treeSession : sessions) {
            sessionArray.add(toSessionSummary(treeSession));
            List<DialogueRunView> runSummaries = executionLedgerQueryService.querySessionRuns(
                    treeSession.getSessionId(),
                    0,
                    MAX_REQUEST_IDS + 1
            );
            if (runSummaries == null || runSummaries.isEmpty()) {
                continue;
            }
            runCount += runSummaries.size();
            if (runCount > MAX_REQUEST_IDS) {
                throw new IllegalArgumentException("会话树超过 " + MAX_REQUEST_IDS + " 个 Run，无法复盘");
            }
            for (DialogueRunView runSummary : runSummaries) {
                if (!isTerminal(runSummary.getStatus())) {
                    throw new IllegalArgumentException("Run 尚未结束: " + runSummary.getRequestId());
                }
                ExecutionRunDetail detail = executionLedgerQueryService.queryRunDetail(runSummary.getRequestId());
                if (detail == null) {
                    throw new IllegalArgumentException("Run 明细不存在: " + runSummary.getRequestId());
                }
                details.add(detail);
                JSONObject trace = TaskReviewTraceSanitizer.sanitize(
                        TaskReviewTraceExportService.toTrace(detail)
                );
                JSONObject run = new JSONObject();
                run.put("requestId", detail.getRun().getRequestId());
                run.put("sessionId", detail.getRun().getSessionId());
                run.put("trace", trace);
                runArray.add(run);
                JSONArray traceArtifacts = trace.getJSONArray("artifacts");
                if (traceArtifacts != null) {
                    artifactArray.addAll(traceArtifacts);
                }
            }
        }
        if (details.isEmpty()) {
            throw new IllegalArgumentException("会话树中没有已结束的执行 Run");
        }
        bundle.put("sessions", sessionArray);
        bundle.put("runs", runArray);
        bundle.put("artifacts", artifactArray);
        int bytes = bundle.toJSONString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TRACE_BYTES) {
            throw new IllegalArgumentException("Trace Bundle 超过 20MB 限制");
        }
        return bundle;
    }

    private JSONObject toSessionSummary(DialogueSessionView session) {
        JSONObject item = new JSONObject();
        item.put("sessionId", session.getSessionId());
        item.put("parentSessionId", session.getParentSessionId());
        item.put("rootSessionId", session.getRootSessionId());
        item.put("sessionKind", session.getSessionKind());
        item.put("title", session.getTitle());
        item.put("subAgentType", session.getSubAgentType());
        item.put(
                "isSubAgent",
                SessionKind.SUBAGENT.code().equalsIgnoreCase(session.getSessionKind())
        );
        return item;
    }

    /**
     * 返回当前会话实际使用的模型连接配置，供本机评测侧车调用 Judge。
     *
     * <p>该接口仍走会话归属校验；API Key 只在 Java → 本机 Python 的内部链路中
     * 传递，不进入前端或 Trace Bundle。</p>
     */
    public Map<String, Object> modelConfig(String userId, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                userId,
                sessionId
        );
        DialogueSessionView session =
                executionLedgerQueryService.querySession(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        String modelRef = StringUtils.defaultIfBlank(
                session.getModel(),
                LlmModelCatalog.DEFAULT_MODEL
        );
        LLMSettings settings = llmModelCatalog.resolve(modelRef)
                .orElseThrow(() -> new IllegalArgumentException(
                        "当前会话没有可用的模型配置: " + modelRef
                ));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelRef", modelRef);
        result.put("model", settings.getModel());
        result.put("baseUrl", settings.getBaseUrl());
        result.put("apiKey", settings.getApiKey());
        result.put("interfaceUrl", settings.getInterfaceUrl());
        return result;
    }

    private ReviewLoad load(String userId, String sessionId, List<String> requestIds) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (requestIds == null || requestIds.isEmpty()) {
            throw new IllegalArgumentException("requestIds 不能为空");
        }
        List<String> orderedIds = new ArrayList<>(
                new LinkedHashSet<>(requestIds.stream()
                        .filter(StringUtils::isNotBlank)
                        .map(String::trim)
                        .toList())
        );
        if (orderedIds.isEmpty()) {
            throw new IllegalArgumentException("requestIds 不能为空");
        }
        if (orderedIds.size() > MAX_REQUEST_IDS) {
            throw new IllegalArgumentException("一次最多复盘 " + MAX_REQUEST_IDS + " 个 Run");
        }

        conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                userId,
                sessionId
        );
        DialogueSessionView session = executionLedgerQueryService.querySession(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }

        List<ExecutionRunDetail> details = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (String requestId : orderedIds) {
            DialogueRunView summary = executionLedgerQueryService.queryRunSummary(userId, requestId);
            if (summary == null) {
                throw new IllegalArgumentException("Run 不存在或无权访问: " + requestId);
            }
            if (!sessionId.equals(summary.getSessionId())) {
                throw new IllegalArgumentException("Run 不属于当前会话: " + requestId);
            }
            if (!isTerminal(summary.getStatus())) {
                blockers.add("Run 尚未结束: " + requestId);
            }
            ExecutionRunDetail detail = executionLedgerQueryService.queryRunDetail(requestId);
            if (detail == null) {
                throw new IllegalArgumentException("Run 明细不存在: " + requestId);
            }
            details.add(detail);
        }
        return new ReviewLoad(session, List.copyOf(orderedIds), details, blockers);
    }

    private Map<String, Object> runSummary(ExecutionRunDetail detail) {
        DialogueRunView run = detail.getRun();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("requestId", run.getRequestId());
        item.put("status", TaskReviewTraceExportService.statusName(run.getStatus()));
        item.put("agent", run.getEntryAgent());
        item.put("createdAt", run.getCreateTime());
        item.put("startedAt", run.getStartedAt());
        item.put("finishedAt", run.getFinishedAt());
        item.put("durationMs", run.getDurationMs());
        item.put("totalTokens", run.getTotalTokensTotal());
        item.put("llmCallCount", run.getLlmCallCount());
        item.put("toolCount", run.getToolCallCount());
        item.put("subagentCount", subagentCount(detail));
        item.put("artifactCount", run.getArtifactCount());
        item.put("errorCode", run.getErrorCode());
        item.put("errorMsg", run.getErrorMsg());
        return item;
    }

    private Map<String, Object> aggregate(List<ExecutionRunDetail> details) {
        long durationMs = 0L;
        int totalTokens = 0;
        int toolCount = 0;
        int failedToolCount = 0;
        int artifactCount = 0;
        int subagentCount = 0;
        for (ExecutionRunDetail detail : details) {
            DialogueRunView run = detail.getRun();
            durationMs += run.getDurationMs() == null ? 0L : run.getDurationMs();
            totalTokens += run.getTotalTokensTotal() == null ? 0 : run.getTotalTokensTotal();
            toolCount += run.getToolCallCount() == null ? 0 : run.getToolCallCount();
            artifactCount += run.getArtifactCount() == null ? 0 : run.getArtifactCount();
            subagentCount += subagentCount(detail);
            for (ToolInvocationView tool : detail.getToolInvocations()) {
                if (!Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS)
                        .equals(tool.getStatus())) {
                    failedToolCount++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("durationMs", durationMs);
        result.put("totalTokens", totalTokens);
        result.put("toolCount", toolCount);
        result.put("failedToolCount", failedToolCount);
        result.put("artifactCount", artifactCount);
        result.put("subagentCount", subagentCount);
        return result;
    }

    private int subagentCount(ExecutionRunDetail detail) {
        return (int) detail.getToolInvocations().stream()
                .map(ToolInvocationView::getSubAgentId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .count();
    }

    private boolean isTerminal(Integer status) {
        return status != null
                && status != ExecutionLedgerConstants.STATUS_RUNNING
                && status != ExecutionLedgerConstants.STATUS_WAITING_INPUT;
    }

    private record ReviewLoad(
            DialogueSessionView session,
            List<String> requestIds,
            List<ExecutionRunDetail> runs,
            List<String> blockers
    ) {
    }
}
