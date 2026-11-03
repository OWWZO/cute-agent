package org.wwz.ai.domain.agent.runtime.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;

import java.util.Map;

/**
 * Agent runtime 发出的领域流事件。
 * <p>它描述运行事实和 typed payload，不直接承担 HTTP response 的增量包装。</p>
 * <p>{@code resultMap}/{@code ext} 是生产者构造后仍会被填充的可变映射（见
 * {@code AgentSessionPrinter}：先 build 事件再 put agentType/extra），因此这里不做
 * 构造期防御性拷贝；但 {@code equals/hashCode} 排除这两个字段，避免事件进入哈希集合后
 * 因映射被修改而「消失」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent {

    private String requestId;
    private String messageId;
    private Boolean isFinal;
    private String messageType;
    private String digitalEmployee;
    private String messageTime;
    private ReplayTiming timing;
    private String planThought;
    private PlanStreamPayload plan;
    private String task;
    private String taskSummary;
    private String toolThought;
    private String reasoningContent;
    private ToolResultStreamPayload toolResult;
    @EqualsAndHashCode.Exclude
    private Map<String, Object> resultMap;
    private String result;
    private Boolean finish;
    @EqualsAndHashCode.Exclude
    private Map<String, String> ext;
}
