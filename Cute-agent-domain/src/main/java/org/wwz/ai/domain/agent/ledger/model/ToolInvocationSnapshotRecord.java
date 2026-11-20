package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

/**
 * 运行中工具的结构化输出快照。
 *
 * <p>与 {@link ToolInvocationFinishRecord} 不同，本记录只更新 rich output，
 * 不结算 tool invocation 状态和 finishedAt，供刷新后恢复仍在运行的工具阶段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationSnapshotRecord {

    private Long toolInvocationId;
    private Long runId;
    private String requestId;
    private String sessionId;
    private String toolCallId;
    private String toolName;
    private Integer status;

    @ToString.Exclude
    private ToolStructuredOutput structuredOutput;
}
