package org.wwz.ai.domain.agent.adapter.port;

import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 子智能体会话身份参数对象。
 * <p>
 * 用命名访问器取代 {@code SubAgentSessionPort.ensureChildSession} 原来的五个连续
 * {@code String} 位置参数，避免调用点把 {@code childSessionId}/{@code parentSessionId}
 * 或 {@code userId}/{@code title} 调换位置后仍能编译通过、静默落库坏身份行。
 * 各字段的空值语义见 {@link SubAgentSessionPort} 契约。
 */
@Getter
@Builder
public final class SubAgentSessionIdentity {

    /** 确定性子会话 ID（{@code sub_{agentId}}），必填。 */
    private final String childSessionId;

    /** 父会话 ID，仅审计与授权穿透，不参与工作记忆 hydrate，必填。 */
    private final String parentSessionId;

    /** 会话归属用户，与父会话一致。 */
    private final String userId;

    /** 展示标题（子 Agent 任务描述）。 */
    private final String title;

    /** 子智能体类型，决定该会话的工具池；空值表示未记录。 */
    private final String subAgentType;

    /**
     * 校验必填项：{@code childSessionId}/{@code parentSessionId} 缺失即为调用方缺陷。
     */
    @Builder
    public SubAgentSessionIdentity(String childSessionId,
                                   String parentSessionId,
                                   String userId,
                                   String title,
                                   String subAgentType) {
        if (StringUtils.isBlank(childSessionId) || StringUtils.isBlank(parentSessionId)) {
            throw new IllegalArgumentException("childSessionId 与 parentSessionId 不能为空");
        }
        this.childSessionId = childSessionId;
        this.parentSessionId = parentSessionId;
        this.userId = userId;
        this.title = title;
        this.subAgentType = subAgentType;
    }
}
