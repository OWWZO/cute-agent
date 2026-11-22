package org.wwz.ai.application.agent.query;

import lombok.Builder;

/**
 * 会话级偏好的本轮显式取值（模型 / 思考 / 思考强度 / 协作模式 / 主 Agent）。
 * <p>每个字段为 {@code null} 表示「本轮没有显式选择」，落库时保留已有值。</p>
 * <p>本类型不校验字段组合；模式与 Agent 的合法性由查询应用服务统一校验。</p>
 */
@Builder
public record SessionPreferences(
        String model,
        Boolean thinking,
        String thinkingEffort,
        String collaborationMode,
        String agentKey) {

    /** 全部未选择：既不落库，也不改变运行时行为。 */
    public static SessionPreferences none() {
        return new SessionPreferences(null, null, null, null, null);
    }

    /**
     * 是否至少选择了一项；全空时调用方不必写库。
     * <p>新增偏好字段时必须同步扩展本方法，否则该字段不会被持久化。</p>
     */
    public boolean hasAny() {
        return model != null || thinking != null || thinkingEffort != null
                || collaborationMode != null || agentKey != null;
    }
}
