package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通用上下文事件视图。
 *
 * <p>只暴露评测所需的稳定事实，不携带摘要正文、压缩前消息或原始错误文本。</p>
 */
@Getter
@Builder
public class ContextEventView {

    private String eventId;
    private String type;
    private String sessionId;
    private String triggerRequestId;
    private String compactRequestId;
    private String status;
    private String strategy;
    private Integer beforeTokens;
    private Integer afterTokens;
    private Integer beforeMessageCount;
    private Integer afterMessageCount;
    private Integer thresholdTokens;
    private String summaryHash;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
