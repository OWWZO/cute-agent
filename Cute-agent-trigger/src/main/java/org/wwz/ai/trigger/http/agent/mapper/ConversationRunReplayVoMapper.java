package org.wwz.ai.trigger.http.agent.mapper;

import org.wwz.ai.application.agent.stream.AgentStreamFrameMapper;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.trigger.http.agent.vo.AgentStreamResponseVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationRunReplayRespVO;

import java.util.List;

/**
 * Shared HTTP mapping for conversation run replay responses.
 */
public final class ConversationRunReplayVoMapper {

    private static final AgentStreamFrameMapper FRAME_MAPPER = new AgentStreamFrameMapper();
    private static final AgentStreamResponseMapper RESPONSE_MAPPER = new AgentStreamResponseMapper();

    private ConversationRunReplayVoMapper() {
    }

    public static ConversationRunReplayRespVO toRespVO(ConversationRunReplay replay) {
        DialogueRunView run = replay == null ? null : replay.getRun();
        if (run == null) {
            return null;
        }
        return ConversationRunReplayRespVO.builder()
                .runUid(run.getRunUid())
                .requestId(run.getRequestId())
                .sessionId(run.getSessionId())
                .entryAgent(run.getEntryAgent())
                .status(toStatusLabel(run.getStatus()))
                .queryText(run.getQueryText())
                .finalSummaryText(run.getFinalSummaryText())
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .contextUsage(replay.getContextUsage())
                .replayFrames(TimelineItemProjection.hasRenderableEventData(replay.getTimelineItems())
                        ? List.of()
                        : toResponseFrames(replay.getReplayFrames()))
                .timelineItems(replay.getTimelineItems())
                .build();
    }

    public static String toStatusLabel(Integer status) {
        int normalized = status == null ? ExecutionLedgerConstants.STATUS_RUNNING : status;
        return switch (normalized) {
            case ExecutionLedgerConstants.STATUS_SUCCESS -> "SUCCESS";
            case ExecutionLedgerConstants.STATUS_FAILED -> "FAILED";
            case ExecutionLedgerConstants.STATUS_TIMEOUT -> "TIMEOUT";
            case ExecutionLedgerConstants.STATUS_STOPPED -> "STOPPED";
            case ExecutionLedgerConstants.STATUS_WAITING_INPUT -> "WAITING_INPUT";
            default -> "RUNNING";
        };
    }

    private static List<AgentStreamResponseVO> toResponseFrames(List<AgentStreamResult> frames) {
        if (frames == null) {
            return List.of();
        }
        return frames.stream()
                .map(FRAME_MAPPER::toFrame)
                .map(RESPONSE_MAPPER::toResponse)
                .toList();
    }
}
