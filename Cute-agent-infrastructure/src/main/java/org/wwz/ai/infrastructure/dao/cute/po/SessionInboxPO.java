package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class SessionInboxPO {
    private Long id;
    private String sessionId;
    private String kind;
    private String visibility;
    private String state;
    private String idempotencyKey;
    @ToString.Exclude
    private String payloadJson;
    private String sourceRunId;
    private String claimedByRunId;
    private Long leaseUntilMs;
    private Long createdAtMs;
    private Long deliveredAtMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
