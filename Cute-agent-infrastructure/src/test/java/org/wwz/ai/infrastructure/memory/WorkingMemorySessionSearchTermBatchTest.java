package org.wwz.ai.infrastructure.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchRequest;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao;
import org.wwz.ai.infrastructure.dao.cute.WorkingMemoryTermScanHit;
import org.wwz.ai.infrastructure.dao.cute.WorkingMemoryTermScanTerm;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Discovery expansion/fallback scans must use one DAO call without changing hits. */
public class WorkingMemorySessionSearchTermBatchTest {

    private static final String FAILURE_QUERY =
            "Are there restrictions for my disposable card since it does not seem to be working?";
    private static final List<String> EXPANSION_TERMS = List.of("rejected", "declined", "failed");
    private static final List<String> FALLBACK_TERMS =
            List.of("there", "restrictions", "my");

    @Test
    public void expansionUsesOneBatchCallRegardlessOfTermCount() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList()))
                .thenReturn(List.of(message("session-main", 1L, "r1", 0,
                        "Are there restrictions for my disposable card since it does not seem to be working?",
                        "USER", 2)));
        when(dao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> batchHits(invocation.getArgument(2), 20L, 0));

        JSONObject payload = discover(dao, FAILURE_QUERY, 1);

        Assert.assertEquals("fulltext", payload.getString("search_backend"));
        Assert.assertFalse(payload.getBooleanValue("fallback"));
        ArgumentCaptor<List<WorkingMemoryTermScanTerm>> terms = listCaptor();
        verify(dao, times(1)).scanTerms(eq("user-1"), eq(null), terms.capture(), anyList());
        assertTermShape(terms.getValue(), 4);
        verify(dao, times(0)).scanByUserId(any(), any(), anyInt(), anyList());
        verify(dao, times(0)).scanBySession(any(), any(), anyInt(), anyList());
        Assert.assertEquals(1, payload.getIntValue("count"));
    }

    @Test
    public void shorterQueryKeepsOneBatchCallAndNoPerTermScan() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.searchFullTextByUserId(eq("user-1"), eq("billing"), eq(4), anyList()))
                .thenReturn(List.of());
        when(dao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> List.of(termScanHit(message("session-main", 20L, "r20", 0,
                        "billing decision", "USER", 2), 1)));

        JSONObject payload = discover(dao, "billing", 1);

        ArgumentCaptor<List<WorkingMemoryTermScanTerm>> terms = listCaptor();
        verify(dao, times(1)).scanTerms(eq("user-1"), eq(null), terms.capture(), anyList());
        Assert.assertEquals(1, terms.getValue().size());
        verify(dao, times(0)).scanByUserId(any(), any(), anyInt(), anyList());
        verify(dao, times(0)).scanBySession(any(), any(), anyInt(), anyList());
        Assert.assertEquals("token-scan", payload.getString("search_backend"));
        Assert.assertEquals(1, payload.getIntValue("count"));
    }

    @Test
    public void fallbackUsesOneBatchCallAndKeepsBackendAndTerms() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList()))
                .thenReturn(List.of());
        when(dao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> batchHits(invocation.getArgument(2), 20L, 1));

        JSONObject payload = discover(dao, FAILURE_QUERY, 1);

        Assert.assertEquals("token-scan", payload.getString("search_backend"));
        Assert.assertTrue(payload.getBooleanValue("fallback"));
        Assert.assertEquals(FALLBACK_TERMS, payload.getJSONArray("fallback_terms").toJavaList(String.class));
        verify(dao, times(1)).scanTerms(eq("user-1"), eq(null), anyList(), anyList());
        verify(dao, times(0)).scanByUserId(any(), any(), anyInt(), anyList());
        verify(dao, times(0)).scanBySession(any(), any(), anyInt(), anyList());
        Assert.assertEquals(1, payload.getIntValue("count"));
        Assert.assertEquals("session-main",
                payload.getJSONArray("results").getJSONObject(0).getString("session_id"));
    }

    @Test
    public void fullTextUnavailableStillBatchesTermScansAndPreservesHits() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList()))
                .thenThrow(new IllegalStateException("FULLTEXT unavailable"));
        when(dao.scanByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList()))
                .thenReturn(List.of());
        when(dao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> batchHits(invocation.getArgument(2), 20L, 1));

        JSONObject payload = discover(dao, FAILURE_QUERY, 1);

        Assert.assertEquals("token-scan", payload.getString("search_backend"));
        Assert.assertTrue(payload.getBooleanValue("fallback"));
        verify(dao, times(1)).scanByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList());
        verify(dao, times(1)).scanTerms(eq("user-1"), eq(null), anyList(), anyList());
        verify(dao, times(0)).scanBySession(any(), any(), anyInt(), anyList());
        Assert.assertEquals(1, payload.getIntValue("count"));
        Assert.assertEquals(22L, payload.getJSONArray("results").getJSONObject(0)
                .getLongValue("match_message_id"));
    }

    @Test
    public void batchedTermScanUnavailableFallsBackToOneCallPerTerm() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(4), anyList()))
                .thenReturn(List.of());
        when(dao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenThrow(new IllegalStateException("missing batched statement"));
        for (String term : FALLBACK_TERMS) {
            when(dao.scanByUserId(eq("user-1"), eq(term), eq(4), anyList()))
                    .thenReturn(List.of(message("session-" + term, 7L, "r7", 0,
                            FAILURE_QUERY, "USER", 2)));
        }
        for (String term : EXPANSION_TERMS) {
            when(dao.scanByUserId(eq("user-1"), eq(term), eq(4), anyList()))
                    .thenReturn(List.of());
        }

        JSONObject payload = discover(dao, FAILURE_QUERY, 1);

        Assert.assertEquals("token-scan", payload.getString("search_backend"));
        Assert.assertTrue(payload.getBooleanValue("fallback"));
        verify(dao, times(1)).scanTerms(eq("user-1"), eq(null), anyList(), anyList());
        verify(dao, times(6)).scanByUserId(eq("user-1"), any(), eq(4), anyList());
        Assert.assertEquals(1, payload.getIntValue("count"));
    }

    @Test
    public void batchedExpansionCandidatesAreEquivalentToOneCallPerTerm() throws Exception {
        List<WorkingMemorySearchMessage> mainHits = List.of(
                message("session-main", 1L, "r1", 0, "Are there restrictions working", "USER", 2));
        List<WorkingMemorySearchMessage> expansionByTerm = List.of(
                message("session-rejected", 2L, "r2", 0, "card rejected working label: 28", "USER", 2),
                message("session-declined", 3L, "r3", 0, "card declined working label: 29", "USER", 2),
                message("session-failed", 4L, "r4", 0, "card failed working label: 30", "USER", 2));
        List<WorkingMemorySearchMessage> fallbackByTerm = List.of(
                message("session-are", 5L, "r5", 0, "Are there restrictions working", "USER", 2),
                message("session-there", 6L, "r6", 0, "Are there restrictions working", "USER", 2),
                message("session-restrictions", 7L, "r7", 0, "Are there restrictions working", "USER", 2));

        IWorkingMemoryMessageDao batchedDao = mock(IWorkingMemoryMessageDao.class);
        when(batchedDao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(8), anyList()))
                .thenReturn(mainHits);
        when(batchedDao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> orderedBatchHits(
                        invocation.getArgument(2), expansionByTerm, fallbackByTerm));

        IWorkingMemoryMessageDao perTermDao = mock(IWorkingMemoryMessageDao.class);
        when(perTermDao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(8), anyList()))
                .thenReturn(mainHits);
        for (int index = 0; index < EXPANSION_TERMS.size(); index++) {
            when(perTermDao.scanByUserId(eq("user-1"), eq(EXPANSION_TERMS.get(index)), eq(8), anyList()))
                    .thenReturn(expansionByTerm.subList(index, index + 1));
        }
        for (int index = 0; index < FALLBACK_TERMS.size(); index++) {
            when(perTermDao.scanByUserId(eq("user-1"), eq(FALLBACK_TERMS.get(index)), eq(10), anyList()))
                    .thenReturn(fallbackByTerm.subList(index, index + 1));
        }

        JSONObject oldPayload = oneCallPerTermDiscovery(perTermDao, mainHits, expansionByTerm);
        JSONObject newPayload = discover(batchedDao, FAILURE_QUERY, 2);
        assertSameHits(newPayload, oldPayload.toJSONString());
        Assert.assertEquals(hitIds(oldPayload.getJSONArray("results")),
                hitIds(newPayload.getJSONArray("results")));
        Assert.assertEquals(oldPayload.getString("search_backend"),
                newPayload.getString("search_backend"));
        Assert.assertEquals(oldPayload.getBooleanValue("fallback"),
                newPayload.getBooleanValue("fallback"));
        verifyBatchedDaoHasNoPerTermScan(batchedDao);
    }

    @Test
    public void batchedFallbackCandidatesAreEquivalentToOneCallPerTerm() throws Exception {
        List<WorkingMemorySearchMessage> fallbackByTerm = List.of(
                message("session-are", 5L, "r5", 0, "Are there restrictions working", "USER", 2),
                message("session-there", 6L, "r6", 0, "Are there restrictions working", "USER", 2),
                message("session-restrictions", 7L, "r7", 0, "Are there restrictions working", "USER", 2));

        IWorkingMemoryMessageDao batchedDao = mock(IWorkingMemoryMessageDao.class);
        when(batchedDao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(8), anyList()))
                .thenReturn(List.of());
        when(batchedDao.scanTerms(eq("user-1"), eq(null), anyList(), anyList()))
                .thenAnswer(invocation -> orderedBatchHits(
                        invocation.getArgument(2), List.of(), fallbackByTerm));

        IWorkingMemoryMessageDao perTermDao = mock(IWorkingMemoryMessageDao.class);
        when(perTermDao.searchFullTextByUserId(eq("user-1"), eq(FAILURE_QUERY), eq(8), anyList()))
                .thenReturn(List.of());
        for (int index = 0; index < FALLBACK_TERMS.size(); index++) {
            when(perTermDao.scanByUserId(eq("user-1"), eq(FALLBACK_TERMS.get(index)), eq(10), anyList()))
                    .thenReturn(fallbackByTerm.subList(index, index + 1));
        }

        JSONObject expected = oneCallPerTermDiscovery(perTermDao, List.of(), fallbackByTerm);
        JSONObject actual = discover(batchedDao, FAILURE_QUERY, 2);
        assertSameHits(actual, expected.toString());
        Assert.assertEquals("token-scan", actual.getString("search_backend"));
        Assert.assertTrue(actual.getBooleanValue("fallback"));
        Assert.assertEquals(FALLBACK_TERMS, actual.getJSONArray("fallback_terms").toJavaList(String.class));
        verifyBatchedDaoHasNoPerTermScan(batchedDao);
    }

    @Test
    public void sessionScopeBatchesExpansionAndFallbackTogether() {
        IWorkingMemoryMessageDao dao = mock(IWorkingMemoryMessageDao.class);
        when(dao.selectSessionSummary("session-1"))
                .thenReturn(java.util.Map.of("userId", "user-1"));
        when(dao.searchFullTextBySession(eq("session-1"), eq(FAILURE_QUERY), eq(8), anyList()))
                .thenReturn(List.of());
        when(dao.scanTerms(eq(null), eq("session-1"), anyList(), anyList()))
                .thenAnswer(invocation -> batchHits(invocation.getArgument(2), 20L, 1));

        JSONObject payload = JSON.parseObject(new WorkingMemorySessionSearchService(dao).search(
                SessionSearchRequest.builder()
                        .userId("user-1")
                        .sessionId("session-1")
                        .query(FAILURE_QUERY)
                        .limit(2)
                        .build()));

        ArgumentCaptor<List<WorkingMemoryTermScanTerm>> terms = listCaptor();
        verify(dao, times(1)).scanTerms(eq(null), eq("session-1"), terms.capture(), anyList());
        assertTermShape(terms.getValue(), 8);
        Assert.assertEquals("token-scan", payload.getString("search_backend"));
        Assert.assertEquals(1, payload.getIntValue("count"));
    }

    private static JSONObject discover(IWorkingMemoryMessageDao dao, String query, int limit) {
        return JSON.parseObject(new WorkingMemorySessionSearchService(dao).search(
                SessionSearchRequest.builder()
                        .userId("user-1")
                        .query(query)
                        .limit(limit)
                        .build()));
    }

    private static JSONObject oneCallPerTermDiscovery(IWorkingMemoryMessageDao dao,
                                                       List<WorkingMemorySearchMessage> mainHits,
                                                       List<WorkingMemorySearchMessage> termHits)
            throws Exception {
        // The DAO is the fixed per-term candidate table; the accessor replays those rows
        // through the unchanged rankCandidates/deduplicate pipeline.
        Assert.assertTrue(dao != null);
        List<WorkingMemorySearchMessage> ordered = new ArrayList<>(mainHits);
        ordered.addAll(termHits);
        return JSON.parseObject(WorkingMemorySessionSearchTermBatchTestAccessor
                .legacyDiscover(FAILURE_QUERY, 2, ordered, mainHits.size()));
    }

    private static void assertSameHits(JSONObject actual, String expected) {
        JSONObject expectedPayload = JSON.parseObject(expected);
        Assert.assertEquals(hitIds(expectedPayload.getJSONArray("results")),
                hitIds(actual.getJSONArray("results")));
    }

    private static void verifyBatchedDaoHasNoPerTermScan(IWorkingMemoryMessageDao dao) {
        verify(dao, times(0)).scanByUserId(any(), any(), anyInt(), anyList());
        verify(dao, times(0)).scanBySession(any(), any(), anyInt(), anyList());
    }

    private static List<String> hitIds(JSONArray results) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JSONObject result = results.getJSONObject(index);
            ids.add(result.getString("session_id") + ":" + result.getLongValue("match_message_id"));
        }
        return ids;
    }

    private static void assertTermShape(List<WorkingMemoryTermScanTerm> terms, int expectedLimit) {
        Assert.assertEquals(6, terms.size());
        for (int index = 0; index < EXPANSION_TERMS.size(); index++) {
            WorkingMemoryTermScanTerm term = terms.get(index);
            Assert.assertEquals(EXPANSION_TERMS.get(index), term.getQuery());
            Assert.assertEquals(0, term.getTermGroup());
            Assert.assertEquals(expectedLimit, term.getTermLimit());
            Assert.assertEquals(index, term.getTermRank());
        }
        for (int index = 0; index < FALLBACK_TERMS.size(); index++) {
            WorkingMemoryTermScanTerm term = terms.get(EXPANSION_TERMS.size() + index);
            Assert.assertEquals(FALLBACK_TERMS.get(index), term.getQuery());
            Assert.assertEquals(1, term.getTermGroup());
            Assert.assertEquals(Math.min(expectedLimit, 10), term.getTermLimit());
            Assert.assertEquals(EXPANSION_TERMS.size() + index, term.getTermRank());
        }
    }

    private static List<WorkingMemoryTermScanHit> batchHits(List<WorkingMemoryTermScanTerm> terms,
                                                            long startId,
                                                            int groupToEmit) {
        List<WorkingMemoryTermScanHit> hits = new ArrayList<>();
        int emitted = 0;
        for (WorkingMemoryTermScanTerm term : terms) {
            if (term.getTermGroup() != groupToEmit) {
                continue;
            }
            String content = groupToEmit == 0
                    ? "card " + term.getQuery() + " working label: 28"
                    : FAILURE_QUERY;
            hits.add(termScanHit(message("session-main", startId + emitted, "r" + emitted, 0,
                    content, "USER", 2), term.getTermGroup()));
            emitted++;
        }
        return hits;
    }

    private static List<WorkingMemoryTermScanHit> orderedBatchHits(
            List<WorkingMemoryTermScanTerm> terms,
            List<WorkingMemorySearchMessage> expansionByTerm,
            List<WorkingMemorySearchMessage> fallbackByTerm) {
        List<WorkingMemoryTermScanHit> hits = new ArrayList<>();
        int expansionIndex = 0;
        int fallbackIndex = 0;
        for (WorkingMemoryTermScanTerm term : terms) {
            if (term.getTermGroup() == 0) {
                int index = expansionIndex++;
                if (index < expansionByTerm.size()) {
                    hits.add(termScanHit(expansionByTerm.get(index), 0));
                }
            } else if (fallbackIndex < fallbackByTerm.size()) {
                hits.add(termScanHit(fallbackByTerm.get(fallbackIndex++), 1));
            }
        }
        return hits;
    }

    private static WorkingMemoryTermScanHit termScanHit(WorkingMemorySearchMessage source, int termGroup) {
        WorkingMemoryTermScanHit hit = new WorkingMemoryTermScanHit();
        hit.setId(source.getId());
        hit.setSessionId(source.getSessionId());
        hit.setMemoryScope(source.getMemoryScope());
        hit.setRequestId(source.getRequestId());
        hit.setOriginMessageKey(source.getOriginMessageKey());
        hit.setTurnSeq(source.getTurnSeq());
        hit.setSeqNo(source.getSeqNo());
        hit.setContent(source.getContent());
        hit.setRole(source.getRole());
        hit.setTurnStatus(source.getTurnStatus());
        hit.setTermGroup(termGroup);
        return hit;
    }

    private static WorkingMemorySearchMessage message(String sessionId,
                                                       Long id,
                                                       String requestId,
                                                       int seq,
                                                       String content,
                                                       String role,
                                                       int status) {
        return WorkingMemorySearchMessage.builder()
                .id(id)
                .sessionId(sessionId)
                .memoryScope("main")
                .requestId(requestId)
                .originMessageKey(requestId + ":" + seq)
                .turnSeq(1)
                .seqNo(seq)
                .content(content)
                .role(role)
                .turnStatus(status)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<WorkingMemoryTermScanTerm>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
