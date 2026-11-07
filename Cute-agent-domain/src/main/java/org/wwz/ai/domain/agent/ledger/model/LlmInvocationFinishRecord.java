package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 结束 LLM 调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationFinishRecord {

    private Long llmInvocationId;

    private String requestId;

    private String sessionId;

    private Integer status;

    private String responseText;

    /** 模型原生 CoT */
    private String reasoningContent;

    private Integer toolCallCount;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer cachedPromptTokens;

    private Integer promptTextTokens;

    private Integer promptAudioTokens;

    private Integer promptImageTokens;

    private Integer completionTextTokens;

    private Integer completionAudioTokens;

    private Integer reasoningTokens;

    private String finishReason;

    private String errorMsg;

    /** 首个流式有效增量到达时间；非流式或未观测到增量为空 */
    private LocalDateTime firstTokenAt;

    private LocalDateTime finishedAt;

    private String cacheStatus;

    private String cacheRiskFlags;
}

