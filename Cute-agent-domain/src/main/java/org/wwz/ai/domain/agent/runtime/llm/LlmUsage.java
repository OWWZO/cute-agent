package org.wwz.ai.domain.agent.runtime.llm;

import lombok.Builder;
import lombok.Value;

/**
 * 供应商无关的 token usage。nativeUsage 只承载已转换的普通对象，不暴露 SDK 类型。
 * <p>所有计数均为可空：{@code null} 表示「上游未上报该字段」，与 {@code 0}（上报为 0）不同；
 * 聚合/比较前调用方必须判空或使用默认零值兜底，不要直接拆箱。</p>
 * <p>不可变：{@link LlmResponse} 对外承诺响应不可变，这里不能开可变后门。
 * 需要补字段时用 {@code toBuilder()} 派生新实例。</p>
 */
@Value
@Builder(toBuilder = true)
public class LlmUsage {

    Integer promptTokens;
    Integer completionTokens;
    Integer totalTokens;
    Integer cachedPromptTokens;
    Integer promptTextTokens;
    Integer promptAudioTokens;
    Integer promptImageTokens;
    Integer completionTextTokens;
    Integer completionAudioTokens;
    Integer reasoningTokens;
    Object nativeUsage;

    public static LlmUsage empty() {
        return LlmUsage.builder().build();
    }
}
