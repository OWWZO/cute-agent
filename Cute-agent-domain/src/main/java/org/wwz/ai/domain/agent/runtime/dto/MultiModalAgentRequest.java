package org.wwz.ai.domain.agent.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 多模态 Agent 请求模型，承载文本、图片/文件引用及运行参数。
 * <p>{@code streamMode} 是远端 SSE 节流策略，接受以下键（与 cute-tool 的 {@code StreamMode} 一致）：
 * {@code mode}（{@code "general"} 原样流 / {@code "token"} 按 token 数缓冲 / {@code "time"} 按秒缓冲）、
 * {@code token}（token 模式下每 N 个 token 刷一次，整数）、
 * {@code time}（time 模式下每 N 秒刷一次，整数）。当前唯一生产构造点为 {@code MultiModalAgent}，
 * 固定发送 {@code {"mode":"token","token":10}}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiModalAgentRequest {
    private String requestId;
    /** mragQuery 的检索问题，服务端必填（空白会被拒绝）；本端由工具入参 question 填充。 */
    private String question;
    /** 当前 agent 上下文的原始 query；mragQuery 服务端不消费该字段（按未知字段忽略），仅随请求透传。 */
    private String query;
    private Boolean stream;
    private Boolean contentStream;
    private Map<String, Object> streamMode;
}
