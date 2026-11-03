package org.wwz.ai.application.agent.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionFile;

import java.util.List;

/**
 * GPT 查询用例命令。由 Trigger request mapper 创建，不进入 Domain runtime。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GptQueryCommand {

    private String query;
    private String sessionId;
    private String requestId;
    private Integer deepThink;
    private String outputStyle;
    private String traceId;
    private String user;
    private String model;
    private Boolean thinking;
    private String thinkingEffort;
    private List<AgentExecutionFile> sessionFiles;

    /**
     * 当前仅透传给运行时，不参与本用例的 {@code agentType} 推导，也没有与
     * {@code deepThink}/{@code thinking} 的组合校验。
     * <p>{@code agentType} 只由 {@code deepThink != 0} 决定；该字段为 true 时仍可同时提交
     * {@code deepThink=0}。修改组合语义前需要同时核对映射器和运行时策略。</p>
     */
    private Boolean forcePlanMode;
}
