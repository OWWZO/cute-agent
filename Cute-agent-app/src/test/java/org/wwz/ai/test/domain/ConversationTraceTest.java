package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.WorkingMemoryConversationRow;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.time.LocalDateTime;
import java.util.List;

public class ConversationTraceTest {

    @Test
    public void shouldMarkConversationUnavailableWhenSourceFails() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String requestId = "req-conversation-unavailable";
        String sessionId = "session-conversation-unavailable";
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                requestId, sessionId, "hello", ctx.recorder);
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
        ctx.store.failConversationRead = true;

        ExecutionRunDetail detail = ctx.queryService.queryRunDetail(requestId);

        Assert.assertEquals("unavailable", detail.getConversationSource());
        Assert.assertTrue(detail.getConversation().isEmpty());
    }

    @Test
    public void shouldProjectVisibleWorkingMemoryConversationAndSources() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String requestId = "req-conversation-001";
        String sessionId = "session-conversation-001";
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                requestId, sessionId, "contact alice@example.com", ctx.recorder);
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

        LocalDateTime at = LocalDateTime.of(2026, 11, 8, 13, 0);
        ctx.store.conversationRows.add(WorkingMemoryConversationRow.builder()
                .turnId(10L)
                .messageId(101L)
                .sessionId(sessionId)
                .requestId(requestId)
                .runId(runId)
                .turnSeq(1)
                .seqNo(0)
                .role("user")
                .content("contact alice@example.com")
                .messageKind("text")
                .visibility("VISIBLE")
                .tokenEstimate(8)
                .createTime(at)
                .build());
        ctx.store.conversationRows.add(WorkingMemoryConversationRow.builder()
                .turnId(10L)
                .messageId(102L)
                .sessionId(sessionId)
                .requestId(requestId)
                .runId(runId)
                .turnSeq(1)
                .seqNo(1)
                .role("assistant")
                .content("I will look it up")
                .toolCallId("call-1")
                .toolCallsJson("{\"name\":\"lookup\",\"arguments\":{\"apiKey\":\"secret-123\"}}")
                .messageKind("tool_call")
                .visibility("VISIBLE")
                .tokenEstimate(12)
                .createTime(at.plusSeconds(1))
                .build());
        ctx.store.conversationRows.add(WorkingMemoryConversationRow.builder()
                .turnId(10L)
                .messageId(103L)
                .sessionId(sessionId)
                .requestId(requestId)
                .runId(runId)
                .turnSeq(1)
                .seqNo(2)
                .role("tool")
                .content("model only")
                .messageKind("tool_result")
                .visibility("MODEL_ONLY")
                .createTime(at.plusSeconds(2))
                .build());

        ExecutionRunDetail detail = ctx.queryService.queryRunDetail(requestId);

        Assert.assertEquals("working_memory", detail.getConversationSource());
        Assert.assertEquals(2, detail.getConversation().size());
        Assert.assertEquals("user", detail.getConversation().get(0).getRole());
        Assert.assertEquals(requestId, detail.getConversation().get(0).getRequestId());
        Assert.assertEquals(Long.valueOf(runId), detail.getConversation().get(0).getRunId());
        Assert.assertEquals(Integer.valueOf(1), detail.getConversation().get(0).getTurnIndex());
        Assert.assertEquals(Integer.valueOf(0), detail.getConversation().get(0).getSeqNo());
        Assert.assertEquals("call-1", detail.getConversation().get(1).getToolCallId());

        JSONObject trace = EvalFixtureRunnerCli.toTrace("conversation.source", detail);
        Assert.assertEquals("working_memory", trace.getString("conversationSource"));
        Assert.assertEquals(2, trace.getJSONArray("conversation").size());
        Assert.assertEquals(
                "lookup",
                trace.getJSONArray("conversation").getJSONObject(1)
                        .getJSONObject("toolCalls").getString("name")
        );
        Assert.assertEquals("unavailable", trace.getString("environmentSource"));
        Assert.assertEquals("unavailable", trace.getString("snapshotSource"));
        Assert.assertTrue(trace.getJSONArray("environmentEvents").isEmpty());
        Assert.assertTrue(trace.getJSONArray("environmentSnapshots").isEmpty());

        JSONObject sanitized = EvalTraceSanitizer.sanitize(trace);
        String serialized = JSON.toJSONString(sanitized);
        Assert.assertFalse(serialized.contains("alice@example.com"));
        Assert.assertFalse(serialized.contains("secret-123"));

        var runView = ctx.queryService.querySessionRuns(sessionId, 0, 10).get(0);
        ExecutionRunDetail batch = ctx.queryService.queryRunDetails(List.of(runView)).get(0);
        Assert.assertEquals("working_memory", batch.getConversationSource());
        Assert.assertEquals(2, batch.getConversation().size());
    }
}
