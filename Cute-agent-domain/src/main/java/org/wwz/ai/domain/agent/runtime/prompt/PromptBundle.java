package org.wwz.ai.domain.agent.runtime.prompt;

import java.util.List;

/**
 * 一次会话稳定提示词装配结果。
 */
public record PromptBundle(
        String systemPrompt,
        String promptVersion,
        String promptHash,
        List<String> sectionNames
) {

    public PromptBundle {
        sectionNames = sectionNames == null ? List.of() : List.copyOf(sectionNames);
    }
}
