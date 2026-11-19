package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryDetail;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunSummary;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 兼容历史视图必须给超长会话一个硬上限，不能无限回放。
 */
public class ConversationHistoryReplayServiceRunCapTest {

    @Test
    public void longSessionIsCappedAtConfiguredRunLimit() {
        ExecutionLedgerQueryService ledger = mock(ExecutionLedgerQueryService.class);
        DialogueSessionView session = DialogueSessionView.builder()
                .sessionId("s-long")
                .status(1)
                .runCount(10_000)
                .build();
        when(ledger.querySession("s-long")).thenReturn(session);
        when(ledger.querySessionRuns(eq("s-long"), anyInt(), anyInt())).thenAnswer(invocation -> {
            int offset = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            return IntStream.range(offset, offset + limit)
                    .mapToObj(index -> DialogueRunView.builder()
                            .id((long) index)
                            .requestId("request-" + index)
                            .status(1)
                            .queryText("query-" + index)
                            .build())
                    .toList();
        });
        when(ledger.queryRunDetails(anyList())).thenReturn(List.of());
        ReplayProjector projector = mock(ReplayProjector.class);
        when(projector.projectHistoryFrames(any())).thenReturn(List.of());

        ConversationHistoryReplayService service =
                new ConversationHistoryReplayService(ledger, projector, null);
        ConversationHistoryDetail detail = service.queryConversationHistory("s-long");

        Assert.assertEquals(ConversationHistoryReplayService.MAX_HISTORY_RUN_COUNT,
                detail.getRuns().size());
    }

    @Test
    public void historySummaryCarriesLatestMeasuredPromptTokens() {
        ExecutionLedgerQueryService ledger = mock(ExecutionLedgerQueryService.class);
        DialogueSessionView session = DialogueSessionView.builder()
                .sessionId("s-context")
                .status(1)
                .runCount(1)
                .build();
        DialogueRunView run = DialogueRunView.builder()
                .id(1L)
                .requestId("request-context")
                .status(1)
                .queryText("query")
                .build();
        when(ledger.querySessionHistorySummary("s-context")).thenReturn(session);
        when(ledger.querySessionRuns(eq("s-context"), any(), anyInt()))
                .thenReturn(List.of(run));
        when(ledger.queryRunContextUsage(anyList())).thenReturn(Map.of(
                1L, ContextUsagePayload.builder().max(128000).promptTokens(13100).used(13100).build()));

        ConversationHistoryReplayService service =
                new ConversationHistoryReplayService(ledger, null, null);
        ConversationHistoryPage page = service.queryConversationHistoryPage("s-context", 20, null);
        ConversationRunSummary summary = page.getRuns().get(0);

        Assert.assertNotNull(summary.getContextUsage());
        Assert.assertEquals(Integer.valueOf(13100), summary.getContextUsage().getPromptTokens());
    }
}
