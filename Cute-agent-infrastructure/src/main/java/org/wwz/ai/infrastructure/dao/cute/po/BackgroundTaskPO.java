package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BackgroundTaskPO {
    private Long id;
    private String sessionId;
    private String taskId;
    /** 任务类型：local_agent / local_shell / generic。 */
    private String type;
    /** 任务状态：running / completed / stopped / failed / lost。 */
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
    /** 开始时间，Unix epoch 毫秒。 */
    private Long startedAtMs;
    /** 结束时间，Unix epoch 毫秒；未结束时为 null。 */
    private Long endedAtMs;
    /** 通知是否已随工作记忆持久化；0 表示下轮 run 需要补投。 */
    private Boolean notified;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 软删标记：0=有效，1=已删除；数据库列决定默认值。 */
    private Integer deleted;
}
