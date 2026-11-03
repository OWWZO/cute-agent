package org.wwz.ai.domain.agent.runtime.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商无关的 token usage。nativeUsage 只承载已转换的普通对象，不暴露 SDK 类型。
 * <p>所有计数均为可空：{@code null} 表示「上游未上报该字段」，与 {@code 0}（上报为 0）不同；
 * 聚合/比较前调用方必须判空或使用默认零值兜底，不要直接拆箱。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {

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
    private Object nativeUsage;

    public static LlmUsage empty() {
        return new LlmUsage();
    }
}
