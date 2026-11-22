package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;
import lombok.ToString;

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
    @ToString.Exclude
    private String command;
    private String agentId;
    private String agentType;
    /** 子 Agent 对应的真实子会话 ID。 */
    private String childSessionId;
    /** 收到最终回传通知的直接父会话。 */
    private String parentSessionId;
    private String parentRequestId;
    private String parentToolUseId;
    private String parentAgentId;
    /** 创建这条后台任务的当前 Agent。 */
    private String ownerAgentId;
    private String rootSessionId;
    private Integer delegationDepth;
    /** running / waiting_background；任务终态仍由 status 表达。 */
    private String phase;
    /** inline / pool。 */
    private String executionMode;
    /** 后台结果触发的新回合次数。 */
    private Integer closingTurnCount;
    @ToString.Exclude
    private String prompt;
    @ToString.Exclude
    private String output;
    @ToString.Exclude
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
