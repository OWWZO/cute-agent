package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 在已有会话（含子会话）上发起新一轮 run 的请求体。
 * <p>偏好四列与主聊天入口同义：用户在本轮显式选择的值会落库，
 * 不传（{@code null}）则沿用库里已存的值。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionQueryReqVO {

    private String query;

    /** 新客户端必填，用于重复提交幂等和同会话并发转指导。 */
    private String requestId;

    /** 本轮模型 modelId；空=沿用会话已存模型。 */
    private String model;

    /** 本轮是否开启深度思考；空=沿用会话已存值。 */
    private Boolean thinking;

    /** low | medium | high；空=沿用会话已存值。 */
    private String thinkingEffort;

    /** 主模式：normal / cluster；空=沿用会话已存值。 */
    private String collaborationMode;

    /** 普通模式主 Agent key；空=沿用会话已存值。 */
    private String agentKey;

    /** @deprecated 兼容旧客户端，仅用于映射为 collaborationMode。 */
    @Deprecated
    private Integer deepThink;
}
