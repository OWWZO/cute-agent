package org.wwz.ai.domain.agent.runtime.printer;


import org.wwz.ai.domain.agent.runtime.enums.AgentType;

import java.util.Map;

/**
 * Agent 输出端口。
 * <p>领域执行器通过该接口发送增量事件，具体实现负责适配 SSE、日志或测试输出。</p>
 */
public interface Printer {
    /**
     * 发送消息
     *
     * @param messageId       帧标识；为 null 时由实现生成（当前实现使用 UUID）
     * @param messageType     帧类型（如 {@code result} / {@code stream_settle} / {@code tool_call_delta}），
     *                        实现据此选择专属处理并判定终态
     * @param message         消息内容
     * @param digitalEmployee 数字员工标识，可为 null；非空时透传到响应与 resultMap
     * @param isFinal         是否最终帧，原样透传给下游
     */

    void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal);

    /**
     * 发送带扩展结果字段的消息。
     * 主要用于 planner round 这类需要透传稳定标识的场景。
     */
    void send(String messageId,
              String messageType,
              Object message,
              Map<String, Object> extraResultMap,
              String digitalEmployee,
              Boolean isFinal);

    /**
     * 以下便捷重载只是核心方法的参数转发，接口统一提供 default 实现。
     * <p>
     * 所有实现此前都逐字重复这 5 段转发，且没有任何实现改写过语义——
     * 收在接口里后，新增实现只需覆盖两个核心方法。
     */

    /** 只带类型与内容的便捷重载：{@code messageId}/{@code digitalEmployee} 为 null，{@code isFinal} 为 true。 */
    default void send(String messageType, Object message) {
        send(null, messageType, message, null, true);
    }

    /** 带数字员工的便捷重载：{@code messageId} 为 null，{@code isFinal} 为 true。 */
    default void send(String messageType, Object message, String digitalEmployee) {
        send(null, messageType, message, digitalEmployee, true);
    }

    /** 带帧标识与终态标记的便捷重载：{@code digitalEmployee} 为 null。 */
    default void send(String messageId, String messageType, Object message, Boolean isFinal) {
        send(messageId, messageType, message, (String) null, isFinal);
    }

    /**
     * 发送带扩展结果字段的简化消息。
     */
    default void sendWithResultMap(String messageId,
                                   String messageType,
                                   Object message,
                                   Map<String, Object> extraResultMap,
                                   Boolean isFinal) {
        send(messageId, messageType, message, extraResultMap, null, isFinal);
    }

    /**
     * 发送带扩展结果字段的消息（同时指定 digitalEmployee）。
     * <p>{@code extraResultMap} 与 {@code digitalEmployee} 均允许为 null，由实现自行处理。</p>
     */
    default void sendWithResultMap(String messageId,
                                   String messageType,
                                   Object message,
                                   Map<String, Object> extraResultMap,
                                   String digitalEmployee,
                                   Boolean isFinal) {
        send(messageId, messageType, message, extraResultMap, digitalEmployee, isFinal);
    }

    /**
     * 发送带扩展结果字段的最终消息。
     */
    default void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap) {
        send(null, messageType, message, extraResultMap, null, true);
    }

    /** 关闭输出端口，释放底层资源；重复调用应无副作用（当前实现转发为流的 {@code complete()}）。 */
    void close();

    void updateAgentType(AgentType agentType);
}
