package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话摘要查询视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueSessionView {

    private Long id;

    private String sessionId;

    private String userId;

    private String sessionKind;

    private String parentSessionId;

    /** 根会话ID：根会话指向自己，子会话指向树根。{@code parent_session_id} 的派生缓存。 */
    private String rootSessionId;

    /** 子智能体类型，决定该会话的工具池；{@code null} 表示未记录。 */
    private String subAgentType;

    private String title;

    private Integer status;

    private String latestRequestId;

    private String latestQueryText;

    private String latestSummaryText;

    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    /** 会话级模型标识；{@code null} 表示用户从未显式选择过。 */
    private String model;

    /** 是否开启深度思考；{@code null} 表示未显式选择。 */
    private Boolean thinking;

    /** 思考强度（low/medium/high）；{@code null} 表示用默认。 */
    private String thinkingEffort;

    /** Deep Think 开关；派生 {@code agentType}。 */
    private Integer deepThink;

    private LocalDateTime createTime;
}
