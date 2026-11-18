package org.wwz.ai.domain.agent.ledger.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 轻量 LLM 用量行：只供会话摘要恢复 ContextRing，不含响应正文。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageSummary {

    private Long runId;

    private String modelName;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer estTotalTokens;

    private Integer estSystemTokens;

    private Integer estMessageTokens;

    private Integer estToolTokens;
}
