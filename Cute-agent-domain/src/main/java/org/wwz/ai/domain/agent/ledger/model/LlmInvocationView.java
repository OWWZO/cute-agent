package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM 调用查询视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationView {

    private Long id;

    private Long runId;

    private Integer invocationSeq;

    private String agentName;

    private Integer stepNo;

    private String callKind;

    private Integer streaming;

    private String modelName;

    private String responseText;

    /** 模型原生 CoT */
    private String reasoningContent;

    private Integer toolCallCount;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** prompt_tokens_details.cached_tokens */
    private Integer cachedPromptTokens;

    /** prompt_tokens_details.text_tokens */
    private Integer promptTextTokens;

    /** prompt_tokens_details.audio_tokens */
    private Integer promptAudioTokens;

    /** prompt_tokens_details.image_tokens */
    private Integer promptImageTokens;

    /** completion_tokens_details.text_tokens */
    private Integer completionTextTokens;

    /** completion_tokens_details.audio_tokens */
    private Integer completionAudioTokens;

    /** completion_tokens_details.reasoning_tokens */
    private Integer reasoningTokens;

    /** 本地 System Prompt 指纹。 */
    private String systemFingerprint;

    /** 请求开始前的上下文分段估算。 */
    private Integer estTotalTokens;

    private Integer estSystemTokens;

    private Integer estMessageTokens;

    private Integer estToolTokens;

    private Integer messageCount;

    private Integer toolCount;

    /** OK / RISK / MISS / UNKNOWN */
    private String cacheStatus;

    private String cacheRiskFlags;

    private String finishReason;

    private Integer status;

    private String errorMsg;

    private LocalDateTime startedAt;

    /** 首个流式有效增量到达时间；非流式或未观测到增量为空 */
    private LocalDateTime firstTokenAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    private LocalDateTime createTime;
}
