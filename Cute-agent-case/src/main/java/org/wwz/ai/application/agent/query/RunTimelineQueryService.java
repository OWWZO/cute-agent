package org.wwz.ai.application.agent.query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.Comparator;
import java.util.List;

/**
 * 主 Agent 与子 Agent 共用的 run 时间线快照查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunTimelineQueryService {

    private final ConversationHistoryReplayService conversationHistoryReplayService;

    /**
     * 查询 run 的 committed 快照。
     *
     * <p>{@code afterSeq} 是 live transport 游标，不属于 durable timeline order。
     * committed 目录始终全量返回，由 itemId 幂等合并；否则新写入项
     * {@code updated_seq=0} 时会被错误过滤掉。</p>
     */
    public RunTimelineSnapshot query(String userId, String requestId, long afterSeq) {
        ConversationRunReplay replay =
                conversationHistoryReplayService.queryRunReplay(userId, requestId);
        if (replay == null || replay.getRun() == null) {
            return null;
        }
        DialogueRunView run = replay.getRun();
        List<TimelineItemView> items = replay.getTimelineItems().stream()
                .filter(item -> item != null)
                .sorted(Comparator
                        .comparingLong((TimelineItemView item) ->
                                item.getOrderKey() <= 0 ? Long.MAX_VALUE : item.getOrderKey())
                        .thenComparingInt(TimelineItemView::getSubOrder)
                        .thenComparing(TimelineItemView::getItemId,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
        long watermark = Math.max(afterSeq, items.stream()
                .mapToLong(TimelineItemView::getEventSeq)
                .max()
                .orElse(0L));
        return new RunTimelineSnapshot(
                run.getRequestId(),
                run.getSessionId(),
                statusName(run.getStatus()),
                isTerminal(run.getStatus()),
                watermark,
                items
        );
    }

    private boolean isTerminal(Integer status) {
        return status != null && (status == ExecutionLedgerConstants.STATUS_SUCCESS
                || status == ExecutionLedgerConstants.STATUS_FAILED
                || status == ExecutionLedgerConstants.STATUS_STOPPED
                || status == ExecutionLedgerConstants.STATUS_TIMEOUT);
    }

    private String statusName(Integer status) {
        if (status == null) {
            return "RUNNING";
        }
        if (status == ExecutionLedgerConstants.STATUS_SUCCESS) {
            return "SUCCESS";
        }
        if (status == ExecutionLedgerConstants.STATUS_FAILED) {
            return "FAILED";
        }
        if (status == ExecutionLedgerConstants.STATUS_STOPPED) {
            return "STOPPED";
        }
        if (status == ExecutionLedgerConstants.STATUS_TIMEOUT) {
            return "TIMEOUT";
        }
        if (status == ExecutionLedgerConstants.STATUS_WAITING_INPUT) {
            return "WAITING_INPUT";
        }
        return "RUNNING";
    }
}
