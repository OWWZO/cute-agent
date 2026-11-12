package org.wwz.ai.infrastructure.memory;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchRequest;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchService;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes 风格的消息级历史浏览器。搜索只看 main working-memory 投影；READY 与
 * INVALID 都是历史，只有 preload 仍然只读取 READY。
 * <p>
 * 出站统一经过角色过滤与字段白名单：默认隐藏 TOOL，Discovery 只返回命中索引。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class WorkingMemorySessionSearchService implements SessionSearchService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_WINDOW = 5;
    private static final int MAX_WINDOW = 20;
    private static final int FULL_CONTENT_RESULT_LIMIT = 3;
    private static final int MAX_FALLBACK_TOKENS = 3;
    private static final int SEARCH_SNIPPET_MAX_CHARS = 1_200;
    private static final int SEARCH_SNIPPET_CONTEXT_BEFORE = 300;
    private static final int MULTI_WINDOW_RESULT_LIMIT = 2;
    private static final int MULTI_WINDOW_COUNT = 3;
    private static final int LABEL_EVIDENCE_LIMIT = 6;
    private static final int AGGREGATE_LABEL_EVIDENCE_LIMIT = 8;
    private static final int LABEL_SAMPLE_MAX_CHARS = 360;
    private static final Pattern LABEL_SAMPLE_PATTERN = Pattern.compile(
            "(?m)(?:^|\\r?\\n)\\s*([^\\r\\n]{1,500}?)\\s*(?:\\r?\\n\\s*)?label\\s*:\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> FAILURE_INTENT_TERMS = Set.of(
            "work", "fail", "failure", "declin", "reject", "error", "issu",
            "broken", "pend", "cancel", "accept", "process");
    private static final Set<String> FAILURE_QUERY_CUES = Set.of(
            "not", "fail", "failure", "declin", "reject", "error", "issu",
            "broken", "pend", "cancel", "won", "doesn", "isn");
    private static final Set<String> RESTRICTION_INTENT_TERMS = Set.of(
            "restriction", "limitation", "limit", "allow", "availabl");
    private static final Set<String> SEARCH_STOP_WORDS = Set.of(
            "a", "an", "and", "archival", "are", "as", "at", "be", "below", "by",
            "complete", "context", "did", "do", "does", "for", "from", "had", "has",
            "have", "how", "in", "is", "it", "memory", "of", "on", "or", "question",
            "search", "task", "that", "the", "this", "to", "was", "were", "what",
            "when", "where", "which", "who", "why", "with");
    /**
     * Long-context memory chunks can exceed 4k tokens (roughly 16k chars).
     * Keep the complete chunk visible so retrieval does not turn into repeated
     * partial searches for the same evidence.
     */
    private static final int MAX_CONTENT_CHARS = 20_000;
    /** working_memory_turn.status INVALID = compacted-away; still searchable, not in live context. */
    private static final int TURN_STATUS_INVALID = 2;

    private final IWorkingMemoryMessageDao workingMemoryMessageDao;

    @Override
    public String search(SessionSearchRequest request) {
        if (request == null) {
            // 有意设计：搜索框允许空条件查询，null 请求等价于「浏览全部」，不是参数错误。
            return browse(null, null, DEFAULT_LIMIT);
        }
        if (request.getLimit() != null && request.getLimit() <= 0) {
            return error("limit must be >= 1");
        }
        String sessionId = trim(request.getSessionId());
        Long anchor = request.getAroundMessageId();
        String query = trim(request.getQuery());
        String userId = trim(request.getUserId());
        if (anchor != null && sessionId == null) {
            return error("around_message_id requires session_id");
        }
        if (query != null && sessionId != null && anchor != null) {
            return error("query cannot be combined with session_id and around_message_id");
        }

        int limit = resolveLimit(request.getLimit());
        List<String> roles = resolveRoles(request.getRoleFilter());
        if (sessionId != null && anchor != null) {
            if (request.getWindow() != null && request.getWindow() <= 0) {
                return error("window must be >= 1");
            }
            return scroll(sessionId, userId, anchor, resolveWindow(request.getWindow()), roles);
        }
        if (query != null && sessionId != null) {
            return discover(sessionId, request.getCurrentSessionId(), userId, query, limit, "session", roles);
        }
        if (sessionId != null) {
            return read(sessionId, userId, roles);
        }
        if (query == null) {
            return browse(userId, request.getCurrentSessionId(), limit);
        }
        return discover(null, request.getCurrentSessionId(), userId, query, limit, request.getScope(), roles);
    }

    /** Legacy callers remain supported; the tool uses the request overload above. */
    @Override
    public String search(String sessionId, String userId, String query, int limit, String scope) {
        return search(SessionSearchRequest.builder()
                .currentSessionId(sessionId)
                .userId(userId)
                .query(query)
                .limit(limit <= 0 ? null : limit)
                .scope(scope)
                .build());
    }

    private String discover(String requestedSessionId,
                            String currentSessionId,
                            String userId,
                            String query,
                            int limit,
                            String scope,
                            List<String> roles) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return discoverPayload(query, roles, List.of(), "none", false, List.of());
        }
        List<String> matchingTokens = searchTokens(tokens);
        String normalizedScope = normalizeScope(scope);
        String sessionScopeId = firstNonBlank(requestedSessionId, currentSessionId);
        if ("session".equals(normalizedScope)) {
            if (sessionScopeId == null) {
                return error("session_id is required for scope=session");
            }
            String denied = requireOwnedSession(sessionScopeId, userId);
            if (denied != null) {
                return denied;
            }
        }

        int scanLimit = Math.min(100, Math.max(limit * 4, limit));
        List<WorkingMemorySearchMessage> raw;
        String backend;
        try {
            raw = new ArrayList<>("session".equals(normalizedScope)
                    ? workingMemoryMessageDao.searchFullTextBySession(sessionScopeId, query, scanLimit, roles)
                    : searchByUserIdOrSession(userId, currentSessionId, query, scanLimit, true, roles));
            backend = "fulltext";
        } catch (Exception e) {
            backend = "scan";
            log.warn("working-memory FULLTEXT unavailable, falling back to scan: {}", e.toString());
            try {
                raw = new ArrayList<>("session".equals(normalizedScope)
                        ? workingMemoryMessageDao.scanBySession(sessionScopeId, query, scanLimit, roles)
                        : searchByUserIdOrSession(userId, currentSessionId, query, scanLimit, false, roles));
            } catch (Exception scanError) {
                log.warn("working-memory scan failed: {}", scanError.toString());
                return error("failed to search message history: " + messageOf(scanError));
            }
        }
        for (String expansionTerm : intentExpansionTerms(matchingTokens)) {
            try {
                raw.addAll("session".equals(normalizedScope)
                        ? workingMemoryMessageDao.scanBySession(
                                sessionScopeId, expansionTerm, scanLimit, roles)
                        : searchByUserIdOrSession(
                                userId, currentSessionId, expansionTerm, scanLimit, false, roles));
            } catch (Exception scanError) {
                log.warn("working-memory intent expansion failed term={}: {}",
                        expansionTerm, scanError.toString());
            }
        }

        List<WorkingMemorySearchMessage> matched = rankCandidates(raw, query, matchingTokens);
        boolean fallback = matched.isEmpty();
        List<String> fallbackTerms = List.of();
        if (fallback) {
            fallbackTerms = fallbackTokens(matchingTokens);
            List<WorkingMemorySearchMessage> fallbackHits = new ArrayList<>();
            int fallbackLimit = Math.min(scanLimit, Math.max(limit * 2, 10));
            for (String term : fallbackTerms) {
                try {
                    fallbackHits.addAll("session".equals(normalizedScope)
                            ? workingMemoryMessageDao.scanBySession(
                                    sessionScopeId, term, fallbackLimit, roles)
                            : searchByUserIdOrSession(
                                    userId, currentSessionId, term, fallbackLimit, false, roles));
                } catch (Exception scanError) {
                    log.warn("working-memory token fallback failed term={}: {}",
                            term, scanError.toString());
                }
            }
            matched = rankCandidates(fallbackHits, query, matchingTokens);
            backend = "token-scan";
        }
        boolean excludeLiveCurrent = !"session".equals(normalizedScope);
        List<WorkingMemorySearchMessage> hits = deduplicate(matched, limit, currentSessionId, excludeLiveCurrent);
        Map<String, Map<String, Object>> sessionMetadata = loadSessionMetadata(hits);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            results.add(lightweightResult(
                    hits.get(index),
                    query,
                    matchingTokens,
                    sessionMetadata,
                    index < FULL_CONTENT_RESULT_LIMIT,
                    index < MULTI_WINDOW_RESULT_LIMIT));
        }
        return discoverPayload(
                query,
                roles,
                results,
                backend,
                fallback,
                fallbackTerms,
                aggregateLabelEvidence(matched, query, matchingTokens));
    }

    private String discoverPayload(String query,
                                   List<String> roles,
                                   List<Map<String, Object>> results,
                                   String backend,
                                   boolean fallback,
                                   List<String> fallbackTerms) {
        return discoverPayload(
                query, roles, results, backend, fallback, fallbackTerms, List.of());
    }

    private String discoverPayload(String query,
                                   List<String> roles,
                                   List<Map<String, Object>> results,
                                   String backend,
                                   boolean fallback,
                                   List<String> fallbackTerms,
                                   List<Map<String, Object>> labelEvidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("mode", "discover");
        payload.put("query", query);
        payload.put("role_filter", String.join(",", roles));
        payload.put("results", results);
        payload.put("count", results.size());
        payload.put("search_backend", backend);
        payload.put("fallback", fallback);
        if (fallback) {
            payload.put("fallback_terms", fallbackTerms);
        }
        if (!labelEvidence.isEmpty()) {
            payload.put("label_evidence", labelEvidence);
        }
        return JSON.toJSONString(payload);
    }

    private List<WorkingMemorySearchMessage> searchByUserIdOrSession(String userId,
                                                                       String sessionId,
                                                                       String query,
                                                                       int limit,
                                                                       boolean fullText,
                                                                       List<String> roles) {
        if (StringUtils.isNotBlank(userId)) {
            return fullText
                    ? workingMemoryMessageDao.searchFullTextByUserId(userId.trim(), query, limit, roles)
                    : workingMemoryMessageDao.scanByUserId(userId.trim(), query, limit, roles);
        }
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        return fullText
                ? workingMemoryMessageDao.searchFullTextBySession(sessionId, query, limit, roles)
                : workingMemoryMessageDao.scanBySession(sessionId, query, limit, roles);
    }

    private List<WorkingMemorySearchMessage> deduplicate(List<WorkingMemorySearchMessage> raw,
                                                         int limit,
                                                         String currentSessionId,
                                                         boolean excludeLiveCurrent) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, WorkingMemorySearchMessage> byOrigin = new LinkedHashMap<>();
        for (WorkingMemorySearchMessage hit : raw) {
            if (hit == null || StringUtils.isBlank(hit.getSessionId())) {
                continue;
            }
            if (excludeLiveCurrent && shouldSkipLiveCurrentSession(hit, currentSessionId)) {
                continue;
            }
            String key = hit.getSessionId() + ":"
                    + StringUtils.defaultIfBlank(hit.getOriginMessageKey(), hit.stableKey());
            WorkingMemorySearchMessage previous = byOrigin.get(key);
            if (previous == null || compareHistoryOrder(hit, previous) < 0) {
                byOrigin.put(key, hit);
            }
        }

        Map<String, WorkingMemorySearchMessage> bySession = new LinkedHashMap<>();
        for (WorkingMemorySearchMessage hit : byOrigin.values()) {
            bySession.putIfAbsent(hit.getSessionId(), hit);
            if (bySession.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(bySession.values());
    }

    /** Skip current-session hits that are still in the live READY window. */
    private static boolean shouldSkipLiveCurrentSession(WorkingMemorySearchMessage hit, String currentSessionId) {
        if (StringUtils.isBlank(currentSessionId) || hit == null) {
            return false;
        }
        if (!currentSessionId.equals(hit.getSessionId())) {
            return false;
        }
        return !isInvalidTurn(hit.getTurnStatus());
    }

    private static boolean isInvalidTurn(Integer turnStatus) {
        return turnStatus != null && turnStatus == TURN_STATUS_INVALID;
    }

    private Map<String, Object> lightweightResult(WorkingMemorySearchMessage hit,
                                                  String query,
                                                  List<String> tokens,
                                                  Map<String, Map<String, Object>> sessionMetadata,
                                                  boolean includeContent,
                                                  boolean includeMultiWindowEvidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", hit.getSessionId());
        result.put("match_message_id", hit.getId());
        result.put("origin_message_key", hit.getOriginMessageKey());
        result.put("stable_key", hit.stableKey());
        result.put("matched_role", hit.getRole());
        result.put("snippet", snippet(hit.getContent(), query, tokens));
        result.put("matched_terms", List.copyOf(tokens));
        if (includeContent) {
            String content = StringUtils.defaultString(hit.getContent());
            if (content.length() > MAX_CONTENT_CHARS) {
                result.put("content", content.substring(0, MAX_CONTENT_CHARS) + "...");
                result.put("content_truncated", true);
                result.put("original_content_chars", content.length());
            } else {
                result.put("content", content);
                result.put("content_truncated", false);
            }
        }
        if (includeMultiWindowEvidence) {
            List<String> windows = evidenceWindows(hit.getContent(), query, tokens);
            if (windows.size() > 1) {
                result.put("evidence_windows", windows);
            }
        }
        if (hit.getSearchScore() != null) {
            result.put("search_score", hit.getSearchScore());
        }
        addSessionMetadata(result, hit.getSessionId(), sessionMetadata);
        return result;
    }

    private String scroll(String sessionId, String userId, long aroundMessageId, int window, List<String> roles) {
        String denied = requireOwnedSession(sessionId, userId);
        if (denied != null) {
            return denied;
        }
        WorkingMemorySearchMessage anchor;
        try {
            anchor = workingMemoryMessageDao.selectScrollAnchor(sessionId, aroundMessageId);
        } catch (Exception e) {
            return error("failed to load messages: " + messageOf(e));
        }
        if (anchor == null) {
            return error("around_message_id " + aroundMessageId + " not found in session_id " + sessionId);
        }
        if (!roleAllowed(anchor.getRole(), roles)) {
            return error("around_message_id " + aroundMessageId
                    + " is hidden by role_filter; pass a role_filter that includes that message");
        }
        if (isCompactionSummary(anchor.getContent())) {
            return error("around_message_id " + aroundMessageId
                    + " is hidden by role_filter; pass a role_filter that includes that message");
        }

        List<WorkingMemorySearchMessage> before;
        List<WorkingMemorySearchMessage> after;
        try {
            before = new ArrayList<>(safeList(
                    workingMemoryMessageDao.selectScrollBefore(sessionId, aroundMessageId, window, roles)));
            after = safeList(workingMemoryMessageDao.selectScrollAfter(sessionId, aroundMessageId, window, roles));
        } catch (Exception e) {
            return error("failed to load messages: " + messageOf(e));
        }
        before.removeIf(message -> isCompactionSummary(message == null ? null : message.getContent()));
        after = new ArrayList<>(after);
        after.removeIf(message -> isCompactionSummary(message == null ? null : message.getContent()));
        before = applyViewFilter(before, roles);
        after = applyViewFilter(after, roles);
        before.sort(Comparator.comparing(WorkingMemorySearchMessage::getTurnSeq,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkingMemorySearchMessage::getSeqNo,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkingMemorySearchMessage::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        List<WorkingMemorySearchMessage> history = new ArrayList<>(before.size() + 1 + after.size());
        history.addAll(before);
        history.add(anchor);
        history.addAll(after);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("mode", "scroll");
        payload.put("session_id", sessionId);
        payload.put("around_message_id", aroundMessageId);
        payload.put("window", window);
        payload.put("role_filter", String.join(",", roles));
        payload.put("messages", shapeMessages(history, aroundMessageId));
        payload.put("messages_before", before.size());
        payload.put("messages_after", after.size());
        addSessionMetadata(payload, sessionId);
        return JSON.toJSONString(payload);
    }

    private String read(String sessionId, String userId, List<String> roles) {
        String denied = requireOwnedSession(sessionId, userId);
        if (denied != null) {
            return denied;
        }
        List<WorkingMemorySearchMessage> history;
        try {
            history = applyViewFilter(safeList(workingMemoryMessageDao.selectHistoryBySession(sessionId)), roles);
        } catch (Exception e) {
            return error("failed to load session: " + messageOf(e));
        }
        int head = Math.min(20, history.size());
        int tail = Math.min(10, Math.max(0, history.size() - head));
        boolean truncated = head + tail < history.size();
        List<WorkingMemorySearchMessage> selected = new ArrayList<>(history.subList(0, head));
        if (truncated) {
            selected.addAll(history.subList(history.size() - tail, history.size()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("mode", "read");
        payload.put("session_id", sessionId);
        payload.put("role_filter", String.join(",", roles));
        payload.put("message_count", history.size());
        payload.put("truncated", truncated);
        payload.put("messages", shapeMessages(selected, null));
        if (truncated) {
            payload.put("message",
                    "Session has " + history.size() + " visible messages; showing first " + head
                            + " + last " + tail
                            + ". Pass around_message_id (any id above) to scroll the middle.");
        }
        addSessionMetadata(payload, sessionId);
        return JSON.toJSONString(payload);
    }

    private String browse(String userId, String currentSessionId, int limit) {
        if (StringUtils.isBlank(userId)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("success", true);
            empty.put("mode", "browse");
            empty.put("results", List.of());
            empty.put("count", 0);
            return JSON.toJSONString(empty);
        }
        List<Map<String, Object>> rows;
        try {
            rows = safeMapList(workingMemoryMessageDao.selectRecentSessions(trim(userId), limit + 1));
        } catch (Exception e) {
            return error("failed to browse sessions: " + messageOf(e));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sessionId = value(row, "sessionId", "session_id");
            if (StringUtils.isBlank(sessionId) || sessionId.equals(currentSessionId)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("session_id", sessionId);
            result.put("title", value(row, "title"));
            result.put("preview", StringUtils.defaultIfBlank(
                    value(row, "latestQueryText", "latest_query_text"),
                    value(row, "latestSummaryText", "latest_summary_text")));
            result.put("started_at", value(row, "startedAt", "started_at"));
            result.put("last_active_at", value(row, "lastActiveAt", "last_active_at"));
            result.put("message_count", value(row, "messageCount", "message_count"));
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("mode", "browse");
        payload.put("results", results);
        payload.put("count", results.size());
        return JSON.toJSONString(payload);
    }

    private String requireOwnedSession(String sessionId, String userId) {
        if (StringUtils.isBlank(userId)) {
            return error("user identity required");
        }
        try {
            Map<String, Object> row = workingMemoryMessageDao.selectSessionSummary(sessionId);
            if (row == null || row.isEmpty()) {
                return error("session_id " + sessionId + " not found");
            }
            String owner = value(row, "userId", "user_id");
            if (!userId.equals(owner)) {
                return error("session_id " + sessionId + " not found");
            }
        } catch (Exception e) {
            return error("failed to load session: " + messageOf(e));
        }
        return null;
    }

    private void addSessionMetadata(Map<String, Object> target, String sessionId) {
        addSessionMetadata(target, sessionId, null);
    }

    private void addSessionMetadata(Map<String, Object> target,
                                    String sessionId,
                                    Map<String, Map<String, Object>> loadedMetadata) {
        try {
            Map<String, Object> row = loadedMetadata == null
                    ? workingMemoryMessageDao.selectSessionSummary(sessionId)
                    : loadedMetadata.get(sessionId);
            if (row == null || row.isEmpty()) {
                return;
            }
            putIfPresent(target, "title", value(row, "title"));
            putIfPresent(target, "started_at", value(row, "startedAt", "started_at"));
            putIfPresent(target, "last_active_at", value(row, "lastActiveAt", "last_active_at"));
        } catch (Exception e) {
            log.debug("session metadata lookup failed sessionId={}: {}", sessionId, e.toString());
        }
    }

    private Map<String, Map<String, Object>> loadSessionMetadata(List<WorkingMemorySearchMessage> hits) {
        List<String> sessionIds = hits.stream()
                .map(WorkingMemorySearchMessage::getSessionId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> rows;
        try {
            rows = workingMemoryMessageDao.selectSessionSummaries(sessionIds);
        } catch (Exception e) {
            log.debug("batch session metadata lookup failed: {}", e.toString());
            rows = null;
        }
        if (rows == null) {
            // 保留单条查询回退，兼容未实现批量语句的测试替身与旧装配。
            List<Map<String, Object>> fallbackRows = new ArrayList<>(sessionIds.size());
            for (String sessionId : sessionIds) {
                try {
                    Map<String, Object> row = workingMemoryMessageDao.selectSessionSummary(sessionId);
                    if (row != null && !row.isEmpty()) {
                        fallbackRows.add(row);
                    }
                } catch (Exception e) {
                    log.debug("session metadata fallback lookup failed sessionId={}: {}", sessionId, e.toString());
                }
            }
            rows = fallbackRows;
        }

        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        for (Map<String, Object> row : safeMapList(rows)) {
            String sessionId = value(row, "sessionId", "session_id");
            if (StringUtils.isNotBlank(sessionId)) {
                metadata.putIfAbsent(sessionId, row);
            }
        }
        return metadata;
    }

    private List<WorkingMemorySearchMessage> applyViewFilter(List<WorkingMemorySearchMessage> messages,
                                                             List<String> roles) {
        List<WorkingMemorySearchMessage> filtered = new ArrayList<>();
        for (WorkingMemorySearchMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (!roleAllowed(message.getRole(), roles)) {
                continue;
            }
            if (isCompactionSummary(message.getContent())) {
                continue;
            }
            filtered.add(message);
        }
        return filtered;
    }

    private static boolean isCompactionSummary(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        String text = content.stripLeading();
        return text.startsWith("[CONTEXT COMPACTION")
                || text.startsWith("[CONTEXT SUMMARY]:")
                || text.startsWith("[COMPACTION SUMMARY]")
                || text.startsWith("[WM_COMPACTION_SUMMARY]");
    }

    private static List<Map<String, Object>> shapeMessages(List<WorkingMemorySearchMessage> messages, Long anchor) {
        List<Map<String, Object>> shaped = new ArrayList<>();
        for (WorkingMemorySearchMessage message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.getId());
            item.put("stable_key", message.stableKey());
            item.put("role", message.getRole());
            String content = StringUtils.defaultString(message.getContent());
            if (content.length() > MAX_CONTENT_CHARS) {
                item.put("content", content.substring(0, MAX_CONTENT_CHARS) + "...");
                item.put("content_truncated", true);
                item.put("original_content_chars", content.length());
            } else {
                item.put("content", content);
            }
            item.put("seq_no", message.getSeqNo());
            if (anchor != null && anchor.equals(message.getId())) {
                item.put("anchor", true);
            }
            shaped.add(item);
        }
        return shaped;
    }

    private static List<Map<String, Object>> labelEvidence(String content,
                                                            String query,
                                                            List<String> tokens) {
        if (StringUtils.isBlank(content) || !content.toLowerCase(Locale.ROOT).contains("label")) {
            return List.of();
        }
        Matcher matcher = LABEL_SAMPLE_PATTERN.matcher(content);
        Map<String, LabelEvidence> bestByLabel = new LinkedHashMap<>();
        while (matcher.find()) {
            String sample = matcher.group(1).replaceAll("\\s+", " ").trim();
            if (StringUtils.isBlank(sample)) {
                continue;
            }
            String label = matcher.group(2);
            double score = scoreLabelSample(sample, query, tokens);
            LabelEvidence candidate = new LabelEvidence(
                    label,
                    clip(sample, LABEL_SAMPLE_MAX_CHARS),
                    matcher.start(1),
                    score
            );
            LabelEvidence previous = bestByLabel.get(label);
            if (previous == null || candidate.score() > previous.score()) {
                bestByLabel.put(label, candidate);
            }
        }
        List<LabelEvidence> ranked = new ArrayList<>(bestByLabel.values());
        ranked.sort(Comparator
                .comparingDouble(LabelEvidence::score)
                .reversed()
                .thenComparingInt(LabelEvidence::position));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int index = 0; index < Math.min(LABEL_EVIDENCE_LIMIT, ranked.size()); index++) {
            LabelEvidence item = ranked.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", item.label());
            row.put("sample", item.sample());
            row.put("score", Math.round(item.score() * 100.0d) / 100.0d);
            evidence.add(row);
        }
        return evidence;
    }

    private static List<Map<String, Object>> aggregateLabelEvidence(
            List<WorkingMemorySearchMessage> hits,
            String query,
            List<String> tokens) {
        Map<String, Map<String, Object>> bestByLabel = new LinkedHashMap<>();
        for (WorkingMemorySearchMessage hit : safeList(hits)) {
            for (Map<String, Object> item : labelEvidence(hit.getContent(), query, tokens)) {
                String label = String.valueOf(item.get("label"));
                Map<String, Object> previous = bestByLabel.get(label);
                if (previous == null
                        || numericScore(item) > numericScore(previous)) {
                    bestByLabel.put(label, item);
                }
            }
        }
        List<Map<String, Object>> ranked = new ArrayList<>(bestByLabel.values());
        ranked.sort(Comparator
                .comparingDouble(WorkingMemorySessionSearchService::numericScore)
                .reversed());
        return List.copyOf(ranked.subList(
                0,
                Math.min(AGGREGATE_LABEL_EVIDENCE_LIMIT, ranked.size())));
    }

    private static double numericScore(Map<String, Object> item) {
        Object score = item.get("score");
        return score instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private static double scoreLabelSample(String sample, String query, List<String> tokens) {
        Set<String> queryTerms = normalizedTerms(query);
        queryTerms.addAll(normalizedTerms(String.join(" ", tokens == null ? List.of() : tokens)));
        Set<String> sampleTerms = normalizedTerms(sample);
        double score = 0.0d;
        for (String term : queryTerms) {
            if (!sampleTerms.contains(term)) {
                continue;
            }
            score += term.length() >= 7 ? 2.5d : 1.25d;
        }
        String lowerSample = sample.toLowerCase(Locale.ROOT);
        String lowerQuery = StringUtils.defaultString(query).toLowerCase(Locale.ROOT).trim();
        if (!lowerQuery.isEmpty() && lowerSample.contains(lowerQuery)) {
            score += 6.0d;
        }
        if (hasIntentTerm(queryTerms, FAILURE_INTENT_TERMS)
                && hasIntentTerm(sampleTerms, FAILURE_INTENT_TERMS)) {
            score += 5.0d;
        }
        if (hasIntentTerm(queryTerms, RESTRICTION_INTENT_TERMS)
                && hasIntentTerm(sampleTerms, RESTRICTION_INTENT_TERMS)) {
            score += 1.5d;
        }
        return score;
    }

    private static boolean hasIntentTerm(Set<String> terms, Set<String> intentTerms) {
        for (String term : terms) {
            if (intentTerms.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizedTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (StringUtils.isBlank(text)) {
            return terms;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String term = normalizeTerm(raw);
            if (StringUtils.isNotBlank(term) && !SEARCH_STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String normalizeTerm(String value) {
        String term = StringUtils.trimToEmpty(value);
        if (term.length() > 5 && term.endsWith("ing")) {
            term = term.substring(0, term.length() - 3);
        } else if (term.length() > 4 && term.endsWith("ed")) {
            term = term.substring(0, term.length() - 2);
        } else if (term.length() > 4 && term.endsWith("ies")) {
            term = term.substring(0, term.length() - 3) + "y";
        } else if (term.length() > 3 && term.endsWith("s") && !term.endsWith("ss")) {
            term = term.substring(0, term.length() - 1);
        }
        return term;
    }

    private static List<String> evidenceWindows(String content,
                                                String query,
                                                List<String> tokens) {
        String text = StringUtils.defaultString(content);
        if (text.length() <= SEARCH_SNIPPET_MAX_CHARS) {
            return List.of(text);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<Integer> positions = new LinkedHashSet<>();
        addOccurrences(positions, lower, StringUtils.defaultString(query).toLowerCase(Locale.ROOT));
        for (String token : tokens == null ? List.<String>of() : tokens) {
            addOccurrences(positions, lower, StringUtils.defaultString(token).toLowerCase(Locale.ROOT));
        }
        if (positions.isEmpty()) {
            return List.of(snippet(text, query, tokens));
        }

        List<EvidenceWindow> candidates = new ArrayList<>();
        Set<String> queryTerms = normalizedTerms(
                String.join(" ", tokens == null ? List.of() : tokens));
        for (Integer position : positions) {
            int start = Math.max(0, position - SEARCH_SNIPPET_CONTEXT_BEFORE);
            int end = Math.min(text.length(), start + SEARCH_SNIPPET_MAX_CHARS);
            String windowText = text.substring(start, end);
            double score = scoreEvidenceWindow(windowText, queryTerms);
            candidates.add(new EvidenceWindow(start, end, score));
        }
        candidates.sort(Comparator
                .comparingDouble(EvidenceWindow::score)
                .reversed()
                .thenComparingInt(EvidenceWindow::start));

        List<EvidenceWindow> selected = new ArrayList<>();
        Set<Integer> buckets = new LinkedHashSet<>();
        int bucketSize = Math.max(1, SEARCH_SNIPPET_MAX_CHARS / 3);
        for (EvidenceWindow candidate : candidates) {
            if (!buckets.add(candidate.start() / bucketSize)) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= MULTI_WINDOW_COUNT) {
                break;
            }
        }
        selected.sort(Comparator.comparingInt(EvidenceWindow::start));
        List<String> windows = new ArrayList<>(selected.size());
        for (EvidenceWindow window : selected) {
            windows.add((window.start() > 0 ? "..." : "")
                    + text.substring(window.start(), window.end())
                    + (window.end() < text.length() ? "..." : ""));
        }
        return windows;
    }

    private static void addOccurrences(Set<Integer> positions, String text, String needle) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(needle)) {
            return;
        }
        int from = 0;
        int added = 0;
        while (from < text.length() && added < 40) {
            int found = text.indexOf(needle, from);
            if (found < 0) {
                break;
            }
            positions.add(found);
            from = found + Math.max(1, needle.length());
            added++;
        }
    }

    private static double scoreEvidenceWindow(String windowText, Set<String> queryTerms) {
        Set<String> windowTerms = normalizedTerms(windowText);
        double score = 0.0d;
        for (String term : queryTerms) {
            if (windowTerms.contains(term)) {
                score += term.length() >= 7 ? 2.0d : 1.0d;
            }
        }
        return score;
    }

    private static String clip(String value, int maxChars) {
        String text = StringUtils.defaultString(value).strip();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    private static String snippet(String content, String query, List<String> tokens) {
        String text = StringUtils.defaultString(content);
        String needle = StringUtils.defaultString(query);
        String lower = text.toLowerCase(Locale.ROOT);
        int match = StringUtils.isBlank(needle) ? -1 : lower.indexOf(needle.toLowerCase(Locale.ROOT));
        if (match < 0 && tokens != null) {
            for (String token : tokens) {
                if (StringUtils.isBlank(token)) {
                    continue;
                }
                match = lower.indexOf(token.toLowerCase(Locale.ROOT));
                if (match >= 0) {
                    needle = token;
                    break;
                }
            }
        }
        if (text.length() <= SEARCH_SNIPPET_MAX_CHARS || match < 0) {
            return text.length() <= SEARCH_SNIPPET_MAX_CHARS
                    ? text
                    : text.substring(0, SEARCH_SNIPPET_MAX_CHARS) + "...";
        }
        int start = Math.max(0, match - SEARCH_SNIPPET_CONTEXT_BEFORE);
        int end = Math.min(text.length(), start + SEARCH_SNIPPET_MAX_CHARS);
        return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
    }

    private static int compareHistoryOrder(WorkingMemorySearchMessage left, WorkingMemorySearchMessage right) {
        return Comparator.comparing(WorkingMemorySearchMessage::getTurnSeq, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkingMemorySearchMessage::getSeqNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkingMemorySearchMessage::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                .compare(left, right);
    }

    private static String normalizeScope(String scope) {
        return "session".equalsIgnoreCase(StringUtils.trimToEmpty(scope)) ? "session" : "user";
    }

    private static List<String> resolveRoles(String roleFilter) {
        if (StringUtils.isBlank(roleFilter)) {
            return List.of("USER", "ASSISTANT");
        }
        List<String> roles = new ArrayList<>();
        for (String token : roleFilter.split(",")) {
            String normalized = normalizeRoleToken(token);
            if (normalized != null && !roles.contains(normalized)) {
                roles.add(normalized);
            }
        }
        return roles.isEmpty() ? List.of("USER", "ASSISTANT") : roles;
    }

    private static String normalizeRoleToken(String token) {
        String value = StringUtils.trimToEmpty(token).toUpperCase(Locale.ROOT);
        return switch (value) {
            case "USER", "ASSISTANT", "TOOL", "SYSTEM" -> value;
            default -> null;
        };
    }

    private static boolean roleAllowed(String role, List<String> roles) {
        String normalized = normalizeRoleToken(role);
        return normalized != null && roles.contains(normalized);
    }

    private static List<String> tokenize(String query) {
        if (StringUtils.isBlank(query)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : query.trim().split("\\s+")) {
            if (StringUtils.isNotBlank(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static List<WorkingMemorySearchMessage> rankCandidates(
            List<WorkingMemorySearchMessage> raw,
            String query,
            List<String> tokens) {
        List<RankedMessage> ranked = new ArrayList<>();
        String phrase = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        for (WorkingMemorySearchMessage hit : safeList(raw)) {
            String haystack = StringUtils.defaultString(hit.getContent()).toLowerCase(Locale.ROOT);
            int matched = 0;
            for (String token : tokens) {
                if (haystack.contains(token.toLowerCase(Locale.ROOT))) {
                    matched++;
                }
            }
            if (matched == 0) {
                continue;
            }
            ranked.add(new RankedMessage(
                    hit,
                    StringUtils.isNotBlank(phrase) && haystack.contains(phrase),
                    (double) matched / tokens.size()));
        }
        boolean hasFullCoverage = ranked.stream()
                .anyMatch(item -> item.coverage() >= 1.0d);
        if (hasFullCoverage) {
            ranked.removeIf(item -> item.coverage() < 1.0d);
        }
        ranked.sort(Comparator
                .comparing(RankedMessage::phraseMatch, Comparator.reverseOrder())
                .thenComparing(
                        item -> item.message().getSearchScore(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RankedMessage::coverage, Comparator.reverseOrder())
                .thenComparing(
                        item -> item.message().getTurnSeq(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        item -> item.message().getSeqNo(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        item -> item.message().getId(),
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked.stream().map(RankedMessage::message).toList();
    }

    private static List<String> fallbackTokens(List<String> tokens) {
        List<String> candidates = new ArrayList<>();
        for (String token : tokens) {
            if (!candidates.contains(token)) {
                candidates.add(token);
            }
        }
        return List.copyOf(candidates.subList(0, Math.min(MAX_FALLBACK_TOKENS, candidates.size())));
    }

    private static List<String> intentExpansionTerms(List<String> tokens) {
        if (!hasIntentTerm(normalizedTerms(String.join(" ", tokens)), FAILURE_QUERY_CUES)) {
            return List.of();
        }
        return List.of("rejected", "declined", "failed");
    }

    private static List<String> searchTokens(List<String> tokens) {
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            String cleaned = token
                    .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "")
                    .trim();
            if (StringUtils.isBlank(cleaned)
                    || SEARCH_STOP_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean duplicate = result.stream().anyMatch(value -> value.equalsIgnoreCase(cleaned));
            if (!duplicate) {
                result.add(cleaned);
            }
        }
        return result.isEmpty() ? tokens : result;
    }

    private record RankedMessage(WorkingMemorySearchMessage message,
                                 boolean phraseMatch,
                                 double coverage) {
    }

    private record LabelEvidence(String label, String sample, int position, double score) {
    }

    private record EvidenceWindow(int start, int end, double score) {
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int resolveWindow(Integer window) {
        if (window == null) {
            return DEFAULT_WINDOW;
        }
        return Math.max(1, Math.min(window, MAX_WINDOW));
    }

    private static String firstNonBlank(String first, String second) {
        String left = trim(first);
        return left != null ? left : trim(second);
    }

    private static String trim(String value) {
        return StringUtils.trimToNull(value);
    }

    private static String value(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }
        for (String key : keys) {
            Object found = row.get(key);
            if (found != null) {
                return String.valueOf(found);
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static List<WorkingMemorySearchMessage> safeList(List<WorkingMemorySearchMessage> value) {
        return value == null ? List.of() : value;
    }

    private static List<Map<String, Object>> safeMapList(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }

    private static String messageOf(Exception e) {
        return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
    }

    private static String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", message);
        return JSON.toJSONString(payload);
    }
}
