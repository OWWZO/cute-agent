package org.wwz.ai.domain.agent.ledger.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通用记忆事件视图。
 *
 * <p>只暴露评测所需的执行事实，不携带 owner 明文、记忆内容或原始错误文本。</p>
 */
@Getter
@Builder
public class MemoryEventView {

    private String eventId;
    private String type;
    private String sessionId;
    private String triggerRequestId;
    private String forkRequestId;
    private String forkKind;
    private String status;
    private String skipReason;
    private String ownerType;
    private String ownerHash;
    private Integer userTurns;
    private Integer snapshotMessageCount;
    private Integer maxSteps;
    private Long timeoutSeconds;
    private Long durationMs;
    private Integer entriesBefore;
    private Integer entriesAfter;
    private Integer appliedCount;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
