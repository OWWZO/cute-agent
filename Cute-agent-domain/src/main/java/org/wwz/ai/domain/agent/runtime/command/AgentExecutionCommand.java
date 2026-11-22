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
    /**
     * 写入 Execution Ledger 的展示查询。唤醒轮的输入已经持久化在 Session Inbox，
     * 不能再次放进 {@link #query} 触发内存重复追加，但历史回放仍需要原文。
     */
    private String ledgerQueryText;
    private Integer agentType;
    /** 主模式：normal=普通 Agent；cluster=通用协调者。 */
    private String collaborationMode;
    /** 普通模式绑定的主 Agent key；集群模式为空。 */
    private String mainAgentKey;
    /** 会话首轮冻结的稳定 system 前缀。 */
    private String promptSnapshot;
    private String promptVersion;
    private String promptHash;
    private String outputStyle;
    private String basePrompt;
    /**
     * 隔离评测任务的工作区根目录。为空时沿用会话解析出的默认工作区。
     */
    private String workspaceRoot;
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
     * 当前投影是否就是隔离子 Agent 自己的 session 根。
     * <p>父会话必须过滤子 Agent 明细；子会话本身则要保留这些明细。</p>
     */
    private boolean timelineRootSession;

}
