package org.wwz.ai.domain.agent.runtime.llm;

import lombok.Builder;
import lombok.Value;

/**
 * 供应商无关的工具调用结果。
 * <p>属于 LLM 不可变契约的一部分，构造后不可修改。</p>
 */
@Value
@Builder
public class LlmToolCall {

    private String id;
    private String type;
    private String name;
    private String arguments;
}
