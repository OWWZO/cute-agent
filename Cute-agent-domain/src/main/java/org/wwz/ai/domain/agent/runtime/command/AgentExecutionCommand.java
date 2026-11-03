package org.wwz.ai.domain.agent.runtime.command;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

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
    /**
     * 是否流式输出。{@code null} 与 {@code false} 等价（消费方一律用 {@code Boolean.TRUE.equals} 判定），
     * 这里不设默认值以免与上游「未传」语义混淆。
     */
    private Boolean isStream;
    private List<AgentExecutionFile> sessionFiles;
    private String model;
    /** 是否开深度思考；{@code null} 表示沿用模型/会话配置，不能折叠成 false。 */
    private Boolean thinking;
    private String thinkingEffort;
    private String resumeQuestionId;
    private String resumeDesktopControlId;
    private String resumeApprovalId;
    private String resumeContextJson;
    /** 是否强制进入 plan mode；{@code null} 与 {@code false} 等价。 */
    private Boolean forcePlanMode;

    /** 触发来源（user / background_task / ...），仅观测用，不参与执行语义。 */
    private String triggerSource;

    /**
     * 预装载到 Agent memory 的结构化消息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
        private String messageType;
        private List<ToolCall> toolCalls;
        private String toolCallId;
        private List<JSONObject> artifactRefs;
        private Boolean referenceOnly;
        private String commandCode;
        private List<AgentExecutionFile> uploadFile;
        private List<AgentExecutionFile> files;
    }
}
