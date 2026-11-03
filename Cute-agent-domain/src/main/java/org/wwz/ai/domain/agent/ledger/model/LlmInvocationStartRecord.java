package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建 LLM 调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationStartRecord {

    private Long runId;

    /** 账本关联与幂等键，必填。 */
    private String requestId;

    /** run 内调用序号，必填。 */
    private Integer invocationSeq;

    private String agentName;

    private Integer stepNo;

    private String callKind;

    private Boolean streaming;

    private String modelName;

    private LocalDateTime startedAt;

    private String systemFingerprint;

    private Integer estTotalTokens;

    private Integer estSystemTokens;

    private Integer estMessageTokens;

    private Integer estToolTokens;

    private Integer messageCount;

    private Integer toolCount;

    private String cacheStatus;

    private String cacheRiskFlags;
}

