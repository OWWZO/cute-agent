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

    /** 所属会话；用于同步写时间线目录。 */
    private String sessionId;

    /** run 内调用序号，必填。 */
    private Integer invocationSeq;

    private String agentName;

    /** 时间线目录使用的运行时 Agent ID：main 或子 Agent 运行时 ID。 */
    private String agentId;

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

