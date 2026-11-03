package org.wwz.ai.domain.agent.runtime.llm;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 供应商无关的流式增量事件。
 */
@Data
@NoArgsConstructor
public class LlmStreamEvent {

    private String content;
    private String reasoningContent;
    private List<LlmToolCall> toolCalls;
    private String finishReason;
    private LlmUsage usage;

    /** 构造时复制工具调用列表，避免生产者事后清空/复用列表影响已发出的事件。 */
    @Builder
    private LlmStreamEvent(String content,
                           String reasoningContent,
                           List<LlmToolCall> toolCalls,
                           String finishReason,
                           LlmUsage usage) {
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        this.finishReason = finishReason;
        this.usage = usage;
    }
}
