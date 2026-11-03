package org.wwz.ai.application.agent.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Case 层流 frame。字段名称保持现有浏览器增量协议，Trigger 只负责把它序列化为 HTTP/SSE。
 * <p>该类型是可变对象，并且同一实例会被放入回放缓冲、发布到事件总线并广播给多个观察者。
 * 一旦完成 {@code assignSeq} 或发布，生产者和消费方都必须把它视为只读，不能修改字段或
 * 其中的 Map/List 内容，否则会破坏回放结果并引发并发序列化问题。</p>
 * <p>协议字段当前使用自由字符串，新增取值应保持向后兼容；客户端应忽略未知的
 * {@code packageType}，不要因未知值中断连接。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionStreamFrame {

    /** 运行状态，当前取值为 {@code running}、{@code success}、{@code failed}。 */
    private String status;

    /** 本帧新增的文本增量；来源未设置时可能为 null，客户端不得直接与字符串拼接。 */
    private String response;

    /** 截至本帧的累计文本；来源未设置时可能为 null，客户端应判空后再覆盖展示。 */
    private String responseAll;

    /** 是否为业务终态帧。true 表示本轮内容收口，但不保证传输层立即关闭。 */
    private boolean finished;

    /** 已耗时，单位毫秒；0 是合法值，不表示一定未设置。 */
    private long useTimes;

    /** 已使用 token 数；0 是合法值，不表示一定未设置。 */
    private long useTokens;

    /**
     * 结构化事件数据；正常实时帧非 null，但内容可能与生产者共享且会被后续处理读取。
     * 发布后不得修改，尤其是其中的 {@code eventData} 子 Map。
     */
    private Map<String, Object> resultMap;

    /** 响应内容类型，当前取值为 {@code markdown}、{@code text}、{@code card}。 */
    private String responseType;

    /** 链路追踪 ID；正常实时帧应非 null，历史或缺省路径可能为 null。 */
    private String traceId;

    /** 本次请求 ID；用于关联同一轮执行，可能为 null。 */
    private String reqId;

    /** 当前负载是否按协议加密；该标志不表示字段内容已脱敏。 */
    private boolean encrypted;

    /** 本轮关联的用户查询文本；可能为 null。 */
    private String query;

    /** 运行过程附加消息；可能为 null，且集合可能与上游共享，发布后不得修改。 */
    private List<String> messages;

    /**
     * 包类型，用于区分协议语义。当前已知值为 {@code result}、{@code tool}、
     * {@code stream_settle}、{@code heartbeat}、{@code follow_idle}、{@code follow_pending}。
     */
    private String packageType;

    /** 错误信息；仅失败帧通常有值，成功路径可能为 null。 */
    private String errorMsg;

    /**
     * 会话内单调递增的事件序号，用于断线重放和去重；0 表示尚未赋序或不需要回放的帧
     * （例如心跳），消费方不应把 0 当作有效历史位置。
     */
    private long eventSeq;

    /**
     * 建议重试等待时间，单位毫秒。该字段是包装类型：{@code follow_pending} 的默认构造
     * 可能传 null，因此消费方必须判空，不能直接拆箱。
     */
    private Long retryMs;
}
