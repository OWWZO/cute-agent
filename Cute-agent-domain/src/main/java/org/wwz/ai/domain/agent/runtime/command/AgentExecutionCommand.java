package org.wwz.ai.domain.agent.runtime.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent runtime 的执行命令。
 * <p>该对象只描述一次运行需要的运行时输入，不承担 HTTP 请求或 SSE 响应职责。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionCommand {

    private String requestId;
    private String sessionId;
    private String userId;
    private String erp;
    private String query;
    private Integer agentType;
    private String outputStyle;
    private String basePrompt;
    private List<org.wwz.ai.domain.agent.runtime.dto.Message> workingMemoryMessages;
    private Boolean isStream;
    private List<AgentExecutionFile> sessionFiles;
    private String model;
    private Boolean thinking;
    private String thinkingEffort;
    private String resumeQuestionId;
    private String resumeDesktopControlId;
    private String resumeApprovalId;
    private String resumeContextJson;
    private Boolean forcePlanMode;
}
