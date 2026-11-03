package org.wwz.ai.domain.agent.ledger.model.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 历史回放所需的最小事实集合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayFactBundle implements Serializable {
    private static final long serialVersionUID = 1L;

    private DialogueRunView run;

    @Builder.Default
    private List<LlmInvocationView> llmInvocations = new ArrayList<>();

    @Builder.Default
    private List<ToolInvocationView> toolInvocations = new ArrayList<>();

    @Builder.Default
    private List<ArtifactView> artifacts = new ArrayList<>();

    public List<LlmInvocationView> getLlmInvocations() {
        return llmInvocations == null ? List.of() : Collections.unmodifiableList(llmInvocations);
    }

    public List<ToolInvocationView> getToolInvocations() {
        return toolInvocations == null ? List.of() : Collections.unmodifiableList(toolInvocations);
    }

    public List<ArtifactView> getArtifacts() {
        return artifacts == null ? List.of() : Collections.unmodifiableList(artifacts);
    }
}
