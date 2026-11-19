package org.wwz.ai.domain.agent.ledger.replay;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史回放打印收口。
 */
public class HistoryReplayPrinter {

    public List<AgentStreamResult> ensureReadableConclusion(DialogueRunView run, List<AgentStreamResult> frames) {
        List<AgentStreamResult> result = frames == null ? new ArrayList<>() : new ArrayList<>(frames);
        if (hasReadableConclusion(result) || run == null || StringUtils.isBlank(run.getFinalSummaryText())) {
            return result;
        }
        result.add(buildFallbackConclusion(run));
        return result;
    }

    private boolean hasReadableConclusion(List<AgentStreamResult> frames) {
        if (CollectionUtils.isEmpty(frames)) {
            return false;
        }
        for (AgentStreamResult frame : frames) {
            if (frame == null || frame.getEventData() == null) {
                continue;
            }
            Object eventData = frame.getEventData().get("eventData");
            if (!(eventData instanceof Map<?, ?> eventDataMap)) {
                continue;
            }
            Object resultMap = eventDataMap.get("resultMap");
            if (!(resultMap instanceof Map<?, ?> nestedMap)) {
                continue;
            }
            if (RunFinalEventAssembler.isReadableConclusion(nestedMap, eventDataMap)) {
                return true;
            }
        }
        return false;
    }

    private AgentStreamResult buildFallbackConclusion(DialogueRunView run) {
        return RunFinalEventAssembler.streamResult(run);
    }
}
