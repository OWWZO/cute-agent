package org.wwz.ai.domain.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次工具调用的历史记忆。
 * 保存工具输入、主智能体观察结果和关联文件元信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallMemory {

    private Long toolInvocationId;

    private Long llmInvocationId;

    private String toolCallId;

    private Integer dispatchIndex;

    private String agentName;

    private Integer stepNo;

    private String toolName;

    private String toolProvider;

    /** 工具入参可能含凭证 / 用户数据，禁止进入 toString。 */
    @ToString.Exclude
    private String inputJson;

    /** 原始模型观察结果，禁止进入 toString。 */
    @ToString.Exclude
    private String llmObservation;

    @Builder.Default
    private List<FileArtifactMemory> files = new ArrayList<>();
}
