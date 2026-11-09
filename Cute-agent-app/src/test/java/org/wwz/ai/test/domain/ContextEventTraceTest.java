package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ContextEventView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.time.LocalDateTime;
import java.util.List;

public class ContextEventTraceTest {

    @Test
    public void shouldProjectCompactionFactsWithoutRawAuditText() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String requestId = "req-compaction-001";
        String sessionId = "session-compaction-001";
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                requestId, sessionId, "continue after compaction", ctx.recorder);
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

        LocalDateTime successfulAt = LocalDateTime.of(2026, 11, 8, 10, 0);
        WorkingMemoryCompactionEvent successful = WorkingMemoryCompactionEvent.builder()
                .id(1L)
                .sessionId(sessionId)
                .triggerRequestId(requestId)
                .compactRequestId("compact-1")
                .strategy("full-llm")
                .status(WorkingMemoryCompactionEvent.STATUS_SUCCESS)
                .beforeTokens(1200)
                .afterTokens(300)
                .beforeMessageCount(12)
                .afterMessageCount(3)
                .thresholdTokens(1000)
                .summaryText("secret summary that must not enter trace")
                .beforeMessagesJson("[{\"content\":\"secret history\"}]")
                .afterMessagesJson("[{\"content\":\"secret summary\"}]")
                .createTime(successfulAt)
                .deleted(0)
                .build();
        WorkingMemoryCompactionEvent failed = WorkingMemoryCompactionEvent.builder()
                .id(2L)
                .sessionId(sessionId)
                .triggerRequestId(requestId)
                .strategy("abort-unchanged")
                .status(WorkingMemoryCompactionEvent.STATUS_FAILED)
                .beforeTokens(900)
                .afterTokens(900)
                .beforeMessageCount(9)
                .afterMessageCount(9)
                .thresholdTokens(800)
                .errorMessage("provider timeout with secret details")
                .createTime(successfulAt.plusSeconds(1))
                .deleted(0)
                .build();
        ctx.store.compactionEvents.put(successful.getId(), successful);
        ctx.store.compactionEvents.put(failed.getId(), failed);

        ExecutionRunDetail detail = ctx.queryService.queryRunDetail(requestId);
        List<ContextEventView> events = detail.getContextEvents();

        Assert.assertEquals(2, events.size());
        ContextEventView success = events.get(0);
        Assert.assertEquals("compaction:1", success.getEventId());
        Assert.assertEquals("compaction", success.getType());
        Assert.assertEquals("success", success.getStatus());
        Assert.assertEquals("full-llm", success.getStrategy());
        Assert.assertEquals(Integer.valueOf(1200), success.getBeforeTokens());
        Assert.assertEquals(Integer.valueOf(300), success.getAfterTokens());
        Assert.assertEquals(Integer.valueOf(12), success.getBeforeMessageCount());
        Assert.assertEquals(Integer.valueOf(3), success.getAfterMessageCount());
        Assert.assertEquals(Integer.valueOf(1000), success.getThresholdTokens());
        Assert.assertNull(success.getErrorCode());
        Assert.assertNull(success.getStartedAt());
        Assert.assertEquals(successfulAt, success.getFinishedAt());
        Assert.assertTrue(success.getSummaryHash().startsWith("sha256:"));
        Assert.assertEquals(71, success.getSummaryHash().length());

        ContextEventView failure = events.get(1);
        Assert.assertEquals("failed", failure.getStatus());
        Assert.assertEquals("COMPACTION_TIMEOUT", failure.getErrorCode());
        Assert.assertNull(failure.getSummaryHash());

        JSONObject trace = EvalFixtureRunnerCli.toTrace("context.compaction", detail);
        Assert.assertEquals(2, trace.getJSONArray("contextEvents").size());
        JSONObject successJson = trace.getJSONArray("contextEvents").getJSONObject(0);
        Assert.assertEquals("compaction:1", successJson.getString("eventId"));
        Assert.assertFalse(successJson.containsKey("summaryText"));
        Assert.assertFalse(successJson.containsKey("beforeMessagesJson"));
        Assert.assertFalse(successJson.containsKey("afterMessagesJson"));
        Assert.assertFalse(successJson.containsKey("errorMessage"));
        String serialized = JSON.toJSONString(trace);
        Assert.assertFalse(serialized.contains("secret summary"));
        Assert.assertFalse(serialized.contains("secret history"));
        Assert.assertFalse(serialized.contains("provider timeout"));

        var runView = ctx.queryService.querySessionRuns(sessionId, 0, 10).get(0);
        ExecutionRunDetail batchDetail = ctx.queryService.queryRunDetails(
                List.of(runView)
        ).get(0);
        Assert.assertEquals(2, batchDetail.getContextEvents().size());
    }
}
