package org.wwz.ai.trigger.http.agent.mapper;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.trigger.http.agent.vo.ConversationRunReplayRespVO;

import java.util.List;
import java.util.Map;

public class ConversationRunReplayVoMapperTest {

    @Test
    public void renderableTimelineSuppressesLegacyReplayFrames() {
        ConversationRunReplay replay = ConversationRunReplay.builder()
                .run(run())
                .replayFrames(List.of(frame()))
                .timelineItems(List.of(TimelineItemView.builder()
                        .itemId("run:req-1:final")
                        .payload(Map.of("eventData", Map.of()))
                        .build()))
                .build();

        ConversationRunReplayRespVO response = ConversationRunReplayVoMapper.toRespVO(replay);

        Assert.assertEquals("SUCCESS", response.getStatus());
        Assert.assertEquals("req-1", response.getRequestId());
        Assert.assertTrue(response.getReplayFrames().isEmpty());
        Assert.assertEquals(1, response.getTimelineItems().size());
    }

    @Test
    public void legacyReplayFramesRemainAvailableWhenTimelineHasNoRenderableEventData() {
        ConversationRunReplay replay = ConversationRunReplay.builder()
                .run(run())
                .replayFrames(List.of(frame()))
                .timelineItems(List.of(TimelineItemView.builder()
                        .itemId("run:req-1:final")
                        .payload(Map.of())
                        .build()))
                .build();

        ConversationRunReplayRespVO response = ConversationRunReplayVoMapper.toRespVO(replay);

        Assert.assertEquals(1, response.getReplayFrames().size());
        Assert.assertEquals("hello", response.getReplayFrames().get(0).getResponse());
        Assert.assertEquals(1, response.getTimelineItems().size());
    }

    @Test
    public void statusLabelUsesStablePublicValues() {
        Assert.assertEquals("SUCCESS",
                ConversationRunReplayVoMapper.toStatusLabel(ExecutionLedgerConstants.STATUS_SUCCESS));
        Assert.assertEquals("WAITING_INPUT",
                ConversationRunReplayVoMapper.toStatusLabel(ExecutionLedgerConstants.STATUS_WAITING_INPUT));
        Assert.assertEquals("RUNNING", ConversationRunReplayVoMapper.toStatusLabel(null));
    }

    private static DialogueRunView run() {
        return DialogueRunView.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .build();
    }

    private static AgentStreamResult frame() {
        return AgentStreamResult.builder()
                .requestId("req-1")
                .status("running")
                .contentDelta("hello")
                .content("hello")
                .complete(false)
                .build();
    }
}
