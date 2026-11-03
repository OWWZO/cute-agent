package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次执行明细聚合视图。
 * <p>明细列表以只读副本返回，避免调用方改动聚合对象或与其他视图共享同一列表。</p>
 */
@Getter
@Builder
public class ExecutionRunDetail {

    private DialogueRunView run;

    @Builder.Default
    private List<LlmInvocationView> llmInvocations = List.of();

    @Builder.Default
    private List<ToolInvocationView> toolInvocations = List.of();

    @Builder.Default
    private List<ArtifactView> artifacts = List.of();

    public List<LlmInvocationView> getLlmInvocations() {
        return immutableCopy(llmInvocations);
    }

    public List<ToolInvocationView> getToolInvocations() {
        return immutableCopy(toolInvocations);
    }

    public List<ArtifactView> getArtifacts() {
        return immutableCopy(artifacts);
    }

    private <T> List<T> immutableCopy(List<T> source) {
        return source == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
