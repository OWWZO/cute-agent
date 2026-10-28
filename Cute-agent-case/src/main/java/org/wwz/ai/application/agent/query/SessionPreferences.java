package org.wwz.ai.application.agent.query;

import lombok.Builder;

/**
 * 会话级偏好的本轮显式取值（模型 / 思考 / 思考强度 / Deep Think）。
 * <p>每个字段为 {@code null} 表示「本轮没有显式选择」，落库时保留已有值。</p>
 */
@Builder
public record SessionPreferences(
        String model,
        Boolean thinking,
        String thinkingEffort,
        Integer deepThink) {

    /** 全部未选择：既不落库，也不改变运行时行为。 */
    public static SessionPreferences none() {
        return new SessionPreferences(null, null, null, null);
    }

    /** 是否至少选择了一项；全空时调用方不必写库。 */
    public boolean hasAny() {
        return model != null || thinking != null || thinkingEffort != null || deepThink != null;
    }
}
