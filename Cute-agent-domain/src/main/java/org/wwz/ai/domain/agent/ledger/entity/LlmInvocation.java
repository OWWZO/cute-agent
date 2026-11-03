package org.wwz.ai.domain.agent.ledger.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 单次 LLM 调用账本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocation {

    private Long id;

    /** 所属 run */
    private Long runId;

    /** run 内递增序号 */
    private Integer invocationSeq;

    /** 当前 agent 名称 */
    private String agentName;

    /** 当前步号 */
    private Integer stepNo;

    /**
     * 调用类型，取值见
     * {@link org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants#CALL_KIND_ASK}、
     * {@link org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants#CALL_KIND_ASK_TOOL}、
     * {@link org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants#CALL_KIND_INTERNAL_DIGITAL_EMPLOYEE}、
     * {@link org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants#CALL_KIND_INTERNAL_COMPACT}。
     * <p>该列是自由文本，写入方必须使用上述常量；拼写错误不会报错，但会静默破坏
     * 历史回放与 UI 过滤。</p>
     */
    private String callKind;

    /** 是否流式：{@code 1}=是，{@code 0}=否，{@code null}=上游未记录（未知，不等同于 0）。 */
    private Integer streaming;

    /** 模型名 */
    private String modelName;

    /** 完整响应文本（content，面向用户/过程文） */
    @ToString.Exclude
    private String responseText;

    /** 模型原生 CoT / reasoning_content（与 content 独立） */
    @ToString.Exclude
    private String reasoningContent;

    /** 工具调用数量 */
    private Integer toolCallCount;

    /** prompt token */
    private Integer promptTokens;

    /** completion token */
    private Integer completionTokens;

    /** total token */
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

    private String systemFingerprint;

    private Integer estTotalTokens;

    private Integer estSystemTokens;

    private Integer estMessageTokens;

    private Integer estToolTokens;

    private Integer messageCount;

    private Integer toolCount;

    /** OK / RISK / MISS / UNKNOWN */
    private String cacheStatus;

    private String cacheRiskFlags;

    /** 完成原因 */
    private String finishReason;

    /** 状态 */
    private Integer status;

    /** 错误信息 */
    private String errorMsg;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 耗时 */
    private Long durationMs;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
