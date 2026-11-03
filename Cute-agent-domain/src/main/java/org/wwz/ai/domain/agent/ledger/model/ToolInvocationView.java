package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.time.LocalDateTime;

/**
 * 工具调用查询视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationView {

    private Long id;

    private Long runId;

    private Long llmInvocationId;

    private String requestId;

    private String sessionId;

    private String userId;

    private String toolCallId;

    /** 父 Agent 工具 toolCallId；子 Agent 嵌套工具重放用 */
    private String parentToolCallId;

    private String subAgentId;

    private String subAgentType;

    private String subAgentDescription;

    private Integer dispatchIndex;

    private String agentName;

    private Integer stepNo;

    private String toolName;

    private String toolProvider;

    private String inputJson;

    private String llmObservation;

    /** 工具完整结果 JSON（历史回放用；历史行为 null，读取侧回落到 llmObservation） */
    private String toolResult;

    /**
     * 工具结果统一读取入口：{@code toolResult} 为空白时回落 {@code llmObservation}。
     * <p>各回放投影器必须经由本方法取结果文本，不要自行重写
     * {@code defaultIfBlank(getToolResult(), getLlmObservation())} 回落逻辑，
     * 否则新增消费方直读 {@code getToolResult()} 会把历史行渲染成空回放。</p>
     */
    public String resolveResultText() {
        return StringUtils.defaultIfBlank(toolResult, llmObservation);
    }

    /**
     * rich tool 强类型输出。
     */
    private ToolStructuredOutput structuredOutput;

    /** 执行状态，取值见 {@code ExecutionLedgerConstants.STATUS_*}；可能为 {@code null}，比较前须判空。 */
    private Integer status;

    private String errorMsg;

    private Long durationMs;

    private Integer artifactCount;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;
}
