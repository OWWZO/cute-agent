package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 近期会话摘要返回对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionRespVO {

    private String sessionId;

    private String title;

    private String status;

    /** 会话类型：conversation / subagent。会话树据此区分根与子节点。 */
    private String sessionKind;

    /** 父会话 ID；根会话为 null。前端按它挂子节点。 */
    private String parentSessionId;

    /** 根会话 ID；根会话指向自己。 */
    private String rootSessionId;

    /** 子智能体类型；{@code null} 表示未记录。 */
    private String subAgentType;

    private String latestQueryText;

    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;
}
