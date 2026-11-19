package org.wwz.ai.domain.agent.ledger.replay;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.cute.model.constant.Constants;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * run-final 回放事件的唯一载荷入口。
 *
 * <p>历史帧、canonical timeline 和 readable-conclusion 兜底必须使用同一份
 * messageId、完成态、正文和 artifactKeys，避免切换入口后结论或附件漂移。</p>
 */
final class RunFinalEventAssembler {

    private static final String SUMMARY_SUFFIX = ":summary";

    private RunFinalEventAssembler() {
    }

    static ProjectedReplayEvent projected(DialogueRunView run,
                                          String taskId,
                                          int taskOrder,
                                          int messageOrder) {
        return ProjectedReplayEvent.builder()
                .taskId(taskId)
                .taskOrder(taskOrder)
                .messageId(summaryMessageId(run))
                .messageType("task")
                .messageOrder(messageOrder)
                .resultMap(resultMap(run))
                .build();
    }

    static AgentStreamResult streamResult(DialogueRunView run) {
        Map<String, Object> eventData = eventData(
                run,
                run.getRequestId() + "-summary",
                1,
                1
        );
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("agentType", "history");
        outer.put("multiAgent", new LinkedHashMap<>());
        outer.put("eventData", eventData);
        String summary = summaryText(run);
        return AgentStreamResult.builder()
                .status(Constants.SUCCESS)
                .complete(true)
                .requestId(run.getRequestId())
                .eventData(outer)
                .contentDelta(summary)
                .content(summary)
                .build();
    }

    static Map<String, Object> eventData(DialogueRunView run,
                                         String taskId,
                                         int taskOrder,
                                         int messageOrder) {
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("taskId", taskId);
        eventData.put("taskOrder", taskOrder);
        eventData.put("messageType", "task");
        eventData.put("messageOrder", messageOrder);
        eventData.put("messageId", summaryMessageId(run));
        eventData.put("resultMap", resultMap(run));
        return eventData;
    }

    static Map<String, Object> resultMap(DialogueRunView run) {
        SummaryReplayResultResolver.ResolvedSummary summary =
                SummaryReplayResultResolver.resolve(run.getFinalSummaryText());
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("requestId", run.getRequestId());
        resultMap.put("messageId", summaryMessageId(run));
        resultMap.put("messageTime", messageTime(run));
        resultMap.put("messageType", "result");
        resultMap.put("isFinal", true);
        resultMap.put("finish", run.getStatus() != null
                && run.getStatus() != ExecutionLedgerConstants.STATUS_RUNNING);
        resultMap.put("result", summary.getSummaryText());
        resultMap.put("taskSummary", summary.getSummaryText());
        if (!summary.getArtifactKeys().isEmpty()) {
            resultMap.put("artifactKeys", summary.getArtifactKeys());
        }
        return resultMap;
    }

    static boolean isReadableConclusion(Map<?, ?> resultMap) {
        return isReadableConclusion(resultMap, null);
    }

    static boolean isReadableConclusion(Map<?, ?> resultMap, Map<?, ?> eventDataMap) {
        if (resultMap == null || !"result".equals(resultMap.get("messageType"))) {
            return false;
        }
        if (resultMap.containsKey("isFinal") && !Boolean.TRUE.equals(resultMap.get("isFinal"))) {
            return false;
        }
        if (hasNestedSubAgentParent(resultMap)) {
            return false;
        }
        if (eventDataMap != null && hasNestedSubAgentParent(eventDataMap)) {
            return false;
        }
        Object nested = resultMap.get("resultMap");
        return !(nested instanceof Map<?, ?> nestedMap) || !hasNestedSubAgentParent(nestedMap);
    }

    private static boolean hasNestedSubAgentParent(Map<?, ?> resultMap) {
        Object parentToolUseId = resultMap.get("parentToolUseId");
        return parentToolUseId != null
                && StringUtils.isNotBlank(String.valueOf(parentToolUseId));
    }

    private static String summaryMessageId(DialogueRunView run) {
        return run.getRequestId() + SUMMARY_SUFFIX;
    }

    private static String summaryText(DialogueRunView run) {
        return SummaryReplayResultResolver.resolve(run.getFinalSummaryText()).getSummaryText();
    }

    private static String messageTime(DialogueRunView run) {
        if (run.getFinishedAt() != null) {
            return String.valueOf(run.getFinishedAt()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli());
        }
        if (run.getStartedAt() != null) {
            return String.valueOf(run.getStartedAt()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli());
        }
        return String.valueOf(System.currentTimeMillis());
    }
}
