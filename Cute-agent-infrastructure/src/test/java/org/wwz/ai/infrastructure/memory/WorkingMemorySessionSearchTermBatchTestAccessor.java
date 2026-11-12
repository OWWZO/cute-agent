package org.wwz.ai.infrastructure.memory;

import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Runs rankCandidates/deduplicate with the original per-term candidate order. */
final class WorkingMemorySessionSearchTermBatchTestAccessor {

    private WorkingMemorySessionSearchTermBatchTestAccessor() {
    }

    static String legacyDiscover(String query, int limit,
                                 List<WorkingMemorySearchMessage> candidates,
                                 int mainHitCount) throws Exception {
        Class<?> type = WorkingMemorySessionSearchService.class;
        Object service = newInstance(type);
        Method tokenize = type.getDeclaredMethod("tokenize", String.class);
        Method searchTokens = type.getDeclaredMethod("searchTokens", List.class);
        Method rank = type.getDeclaredMethod("rankCandidates", List.class, String.class, List.class);
        Method deduplicate = type.getDeclaredMethod("deduplicate", List.class, int.class,
                String.class, boolean.class);
        Method payload = type.getDeclaredMethod("discoverPayload", String.class, List.class,
                List.class, String.class, boolean.class, List.class, List.class);
        Method labelEvidence = type.getDeclaredMethod("aggregateLabelEvidence", List.class,
                String.class, List.class);
        tokenize.setAccessible(true);
        searchTokens.setAccessible(true);
        rank.setAccessible(true);
        deduplicate.setAccessible(true);
        payload.setAccessible(true);
        labelEvidence.setAccessible(true);

        List<String> tokens = invoke(tokenize, null, query);
        List<String> matchingTokens = invoke(searchTokens, null, tokens);
        List<WorkingMemorySearchMessage> matched = invoke(rank, null, candidates, query, matchingTokens);
        boolean fallback = matched.isEmpty();
        List<String> fallbackTerms = fallback ? filteredTokens(tokens) : List.of();
        if (fallback) {
            matched = invoke(rank, null, termHitsOnly(candidates, mainHitCount), query, matchingTokens);
        }
        List<WorkingMemorySearchMessage> hits = invoke(deduplicate, service, matched, limit, null, true);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            WorkingMemorySearchMessage hit = hits.get(index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("session_id", hit.getSessionId());
            result.put("match_message_id", hit.getId());
            result.put("origin_message_key", hit.getOriginMessageKey());
            result.put("stable_key", hit.stableKey());
            result.put("matched_role", hit.getRole());
            result.put("snippet", hit.getContent());
            result.put("matched_terms", matchingTokens);
            results.add(result);
        }
        return (String) payload.invoke(service, query, List.of("USER", "ASSISTANT"), results,
                fallback ? "token-scan" : "fulltext", fallback, fallbackTerms,
                invoke(labelEvidence, null, matched, query, matchingTokens));
    }

    private static Object newInstance(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor(Class.forName(
                "org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao"));
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[]{null});
    }

    private static List<String> filteredTokens(List<String> tokens) {
        Set<String> stopWords = Set.of("a", "an", "and", "archival", "are", "as", "at", "be", "below",
                "by", "complete", "context", "did", "do", "does", "for", "from", "had", "has", "have",
                "how", "in", "is", "it", "memory", "of", "on", "or", "question", "search", "task",
                "that", "the", "this", "to", "was", "were", "what", "when", "where", "which", "who",
                "why", "with");
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            String cleaned = token.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").trim();
            if (cleaned.isEmpty() || stopWords.contains(cleaned.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result.subList(0, Math.min(
                WorkingMemorySessionSearchService.MAX_FALLBACK_TERM_COUNT, result.size()));
    }

    private static List<WorkingMemorySearchMessage> termHitsOnly(
            List<WorkingMemorySearchMessage> candidates, int mainHitCount) {
        return candidates.subList(mainHitCount, candidates.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object target, Object... args) throws Exception {
        return (T) method.invoke(target, args);
    }
}
