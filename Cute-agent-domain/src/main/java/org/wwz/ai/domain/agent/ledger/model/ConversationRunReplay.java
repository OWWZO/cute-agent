package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full replay for one execution run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRunReplay {

    private DialogueRunView run;

    private ContextUsagePayload contextUsage;

    /**
     * 完整回放帧可能极多；排除出 toString/equals，避免一次误日志就把所有帧拼成字符串，
     * 也让 equals/hashCode 不再随帧数线性变慢。
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<AgentStreamResult> replayFrames = List.of();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<TimelineItemView> timelineItems = List.of();

    /**
     * 回放帧以只读副本返回，避免调用方通过 getter 改动回放快照的内部状态。
     */
    public List<AgentStreamResult> getReplayFrames() {
        return replayFrames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(replayFrames));
    }

    public List<TimelineItemView> getTimelineItems() {
        return timelineItems == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timelineItems));
    }
}
