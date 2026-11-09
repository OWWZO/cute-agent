package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.MemoryEventView;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkExecutionEvent;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.time.LocalDateTime;
import java.util.List;

public class MemoryEventTraceTest {

    @Test
    public void shouldProjectMemoryForkFactsWithoutRawAuditText() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String requestId = "req-memory-fork-001";
        String sessionId = "session-memory-fork-001";
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                requestId, sessionId, "remember this conversation", ctx.recorder);
        Long runId = ExecutionLedgerFixtureFactory.activateRun(
                context,
                ctx.recorder,
                ExecutionLedgerConstants.ENTRY_AGENT_REACT
        );
        ctx.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("done")
                .finishedAt(LocalDateTime.now())
                .build());

        LocalDateTime at = LocalDateTime.of(2026, 11, 8, 12, 0);
        LtmForkExecutionEvent success = LtmForkExecutionEvent.builder()
                .id(1L)
                .sessionId(sessionId)
                .triggerRequestId(requestId)
                .forkRequestId(requestId + "-flush")
                .forkKind(LtmForkExecutionEvent.KIND_FLUSH)
                .status(LtmForkExecutionEvent.STATUS_SUCCESS)
                .ownerType("USER")
                .ownerId("user-secret-42")
                .userTurns(4)
                .snapshotMessageCount(10)
                .maxSteps(8)
                .timeoutSeconds(30L)
                .durationMs(120L)
                .entriesBefore(3)
                .entriesAfter(5)
                .appliedCount(2)
                .detailJson("{\"added\":[{\"content\":\"secret memory\"}]}")
                .createTime(at)
                .deleted(0)
                .build();
        LtmForkExecutionEvent timeout = LtmForkExecutionEvent.builder()
                .id(2L)
                .sessionId(sessionId)
                .triggerRequestId(requestId)
                .forkRequestId(requestId + "-bg-review")
                .forkKind(LtmForkExecutionEvent.KIND_BG_REVIEW)
                .status(LtmForkExecutionEvent.STATUS_TIMEOUT)
                .ownerType("USER")
                .ownerId("user-secret-42")
                .snapshotMessageCount(10)
                .durationMs(30000L)
                .errorMessage("fork timed out with secret details")
                .createTime(at.plusSeconds(1))
                .deleted(0)
                .build();
        LtmForkExecutionEvent skipped = LtmForkExecutionEvent.builder()
                .id(3L)
                .sessionId(sessionId)
                .triggerRequestId(requestId)
                .forkKind(LtmForkExecutionEvent.KIND_BG_REVIEW)
                .status(LtmForkExecutionEvent.STATUS_SKIPPED)
                .skipReason("raw user detail must not leak")
                .snapshotMessageCount(10)
                .createTime(at.plusSeconds(2))
                .deleted(0)
                .build();
        ctx.store.ltmForkEvents.put(success.getId(), success);
        ctx.store.ltmForkEvents.put(timeout.getId(), timeout);
        ctx.store.ltmForkEvents.put(skipped.getId(), skipped);

        ExecutionRunDetail detail = ctx.queryService.queryRunDetail(requestId);
        List<MemoryEventView> events = detail.getMemoryEvents();

        Assert.assertEquals(3, events.size());
        MemoryEventView actualSuccess = events.get(0);
        Assert.assertEquals("memory-fork:1", actualSuccess.getEventId());
        Assert.assertEquals("memory_fork", actualSuccess.getType());
        Assert.assertEquals("flush", actualSuccess.getForkKind());
        Assert.assertEquals("success", actualSuccess.getStatus());
        Assert.assertEquals("USER", actualSuccess.getOwnerType());
        Assert.assertTrue(actualSuccess.getOwnerHash().startsWith("sha256:"));
        Assert.assertEquals(71, actualSuccess.getOwnerHash().length());
        Assert.assertEquals(Integer.valueOf(4), actualSuccess.getUserTurns());
        Assert.assertEquals(Integer.valueOf(10), actualSuccess.getSnapshotMessageCount());
        Assert.assertEquals(Integer.valueOf(8), actualSuccess.getMaxSteps());
        Assert.assertEquals(Long.valueOf(30), actualSuccess.getTimeoutSeconds());
        Assert.assertEquals(Long.valueOf(120), actualSuccess.getDurationMs());
        Assert.assertEquals(Integer.valueOf(3), actualSuccess.getEntriesBefore());
        Assert.assertEquals(Integer.valueOf(5), actualSuccess.getEntriesAfter());
        Assert.assertEquals(Integer.valueOf(2), actualSuccess.getAppliedCount());
        Assert.assertNull(actualSuccess.getErrorCode());
        Assert.assertNull(actualSuccess.getStartedAt());
        Assert.assertEquals(at, actualSuccess.getFinishedAt());

        MemoryEventView actualTimeout = events.get(1);
        Assert.assertEquals("timeout", actualTimeout.getStatus());
        Assert.assertEquals("MEMORY_FORK_TIMEOUT", actualTimeout.getErrorCode());

        MemoryEventView actualSkipped = events.get(2);
        Assert.assertEquals("skipped", actualSkipped.getStatus());
        Assert.assertEquals("other", actualSkipped.getSkipReason());

        JSONObject trace = EvalFixtureRunnerCli.toTrace("memory.fork", detail);
        Assert.assertEquals(3, trace.getJSONArray("memoryEvents").size());
        JSONObject successJson = trace.getJSONArray("memoryEvents").getJSONObject(0);
        Assert.assertFalse(successJson.containsKey("ownerId"));
        Assert.assertFalse(successJson.containsKey("errorMessage"));
        Assert.assertFalse(successJson.containsKey("detailJson"));
        String serialized = JSON.toJSONString(trace);
        Assert.assertFalse(serialized.contains("user-secret-42"));
        Assert.assertFalse(serialized.contains("secret memory"));
        Assert.assertFalse(serialized.contains("fork timed out"));
        Assert.assertFalse(serialized.contains("raw user detail"));

        var runView = ctx.queryService.querySessionRuns(sessionId, 0, 10).get(0);
        ExecutionRunDetail batchDetail = ctx.queryService.queryRunDetails(
                List.of(runView)
        ).get(0);
        Assert.assertEquals(3, batchDetail.getMemoryEvents().size());
    }
}
