package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BackgroundTaskPO {
    private Long id;
    private String sessionId;
    private String taskId;
    private String type;
    private String status;
    private String description;
    private String command;
    private String agentId;
    private String agentType;
    private String prompt;
    private String output;
    private String errorMsg;
    private Integer totalToolUseCount;
    private Long totalDurationMs;
    private Long startedAtMs;
    private Long endedAtMs;
    /** 通知是否已随工作记忆持久化；0 表示下轮 run 需要补投。 */
    private Boolean notified;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
