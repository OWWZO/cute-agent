package org.wwz.ai.application.agent.query;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.List;

public class RunTimelineQueryServiceTest {

    @Test
    public void transportCursorDoesNotFilterCommittedSnapshot() {
        ConversationHistoryReplayService replayService =
                Mockito.mock(ConversationHistoryReplayService.class);
        Mockito.when(replayService.queryRunReplay("user-1", "request-1"))
                .thenReturn(ConversationRunReplay.builder()
                        .run(DialogueRunView.builder()
                                .requestId("request-1")
                                .sessionId("session-1")
                                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                                .build())
                        .timelineItems(List.of(
                                item("tool:1", 1),
                                item("tool:2", 3)
                        ))
                        .build());

        RunTimelineSnapshot snapshot =
                new RunTimelineQueryService(replayService)
                        .query("user-1", "request-1", 1);

        Assert.assertTrue(snapshot.terminal());
        Assert.assertEquals("SUCCESS", snapshot.status());
        Assert.assertEquals(1, snapshot.watermark());
        Assert.assertEquals(2, snapshot.items().size());
        Assert.assertEquals("tool:1", snapshot.items().get(0).getItemId());
        Assert.assertEquals("tool:2", snapshot.items().get(1).getItemId());
    }

    @Test
    public void unauthorizedRunReturnsNull() {
        ConversationHistoryReplayService replayService =
                Mockito.mock(ConversationHistoryReplayService.class);

        Assert.assertNull(new RunTimelineQueryService(replayService)
                .query("user-1", "missing", 0));
    }

    @Test
    public void waitingInputKeepsRunOpenAndExposesBusinessStatus() {
        ConversationHistoryReplayService replayService =
                Mockito.mock(ConversationHistoryReplayService.class);
        Mockito.when(replayService.queryRunReplay("user-1", "request-1"))
                .thenReturn(ConversationRunReplay.builder()
                        .run(DialogueRunView.builder()
                                .requestId("request-1")
                                .sessionId("session-1")
                                .status(ExecutionLedgerConstants.STATUS_WAITING_INPUT)
                                .build())
                        .timelineItems(List.of())
                        .build());

        RunTimelineSnapshot snapshot =
                new RunTimelineQueryService(replayService)
                        .query("user-1", "request-1", 0);

        Assert.assertFalse(snapshot.terminal());
        Assert.assertEquals("WAITING_INPUT", snapshot.status());
    }

    private TimelineItemView item(String itemId, long seq) {
        return TimelineItemView.builder()
                .itemId(itemId)
                .seq(seq)
                .eventSeq(0L)
                .orderKey(seq)
                .build();
    }
}
