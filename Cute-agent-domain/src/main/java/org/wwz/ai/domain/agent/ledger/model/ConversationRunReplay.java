package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.runtime.llm.ContextUsagePayload;

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

    @Builder.Default
    private List<AgentStreamResult> replayFrames = List.of();

    /**
     * 回放帧以只读副本返回，避免调用方通过 getter 改动回放快照的内部状态。
     */
    public List<AgentStreamResult> getReplayFrames() {
        return replayFrames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(replayFrames));
    }
}
