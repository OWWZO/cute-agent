package org.wwz.ai.domain.agent.memory.ltm;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 可插拔长期记忆后端。
 * <p><b>线程安全：</b>同一 Provider 实例会被并发调用——{@code prefetch} 在 {@code ltm-prefetch}
 * 线程执行，{@code queuePrefetch}/{@code syncTurn}/{@code onSessionEnd}/{@code onSessionSwitch}
 * 在 {@code ltm-bg} 单线程执行，{@code initialize}/{@code systemPromptBlock}/{@code handleToolCall}
 * 等在调用方线程执行；不同类别之间可能重叠。实现必须自行保证线程安全，不得依赖 ThreadLocal
 * 或无保护的实例可变状态跨钩子传递数据。</p>
 * <p><b>返回值：</b>{@code String} 用 {@code ""}、{@code List} 用空列表表示「无结果」，
 * 实现不应返回 {@code null}。</p>
 */
public interface MemoryProvider {

    String name();

    /**
     * 是否占用唯一的外部 Provider 槽位。
     * <p>必须显式声明为 {@code true}：默认值不能让一个本地/进程内 Provider 意外抢占
     * 全局外部槽位，从而拒绝真正的远程记忆后端。</p>
     */
    default boolean isExternal() {
        return false;
    }

    default boolean isAvailable() {
        return true;
    }

    default void initialize(String sessionId, LtmOwner owner, Map<String, Object> context) {
    }

    default String systemPromptBlock() {
        return "";
    }

    default String prefetch(String query, String sessionId) {
        return "";
    }

    default void queuePrefetch(String query, String sessionId) {
    }

    default void syncTurn(String userContent, String assistantContent, String sessionId, List<Map<String, Object>> messages) {
    }

    default void onSessionEnd(List<Map<String, Object>> messages) {
    }

    /**
     * 会话切换回调。
     *
     * @param reset   是否重置与会话绑定的事实；{@code true} 表示旧会话的上下文不应继续沿用
     * @param rewound 预留的「回退到历史会话」标记；当前 {@code LtmManager} 恒传 {@code false}，
     *                实现不应依赖它为 {@code true} 的分支。两者同时为 {@code true} 时以 {@code reset} 为准
     */
    default void onSessionSwitch(String newSessionId, String parentSessionId, boolean reset, boolean rewound) {
    }

    default String onPreCompress(List<Map<String, Object>> messages) {
        return "";
    }

    default void onMemoryWrite(String action, String target, String content, Map<String, Object> metadata) {
    }

    /**
     * 对外暴露的工具 schema。返回非空列表的实现必须同时覆写 {@link #handleToolCall}，
     * 否则工具被调用时只会抛 {@link UnsupportedOperationException}。
     */
    default List<Map<String, Object>> getToolSchemas() {
        return Collections.emptyList();
    }

    /**
     * 处理 {@link #getToolSchemas()} 暴露的工具；默认实现不支持任何工具调用。
     */
    default String handleToolCall(String toolName, Map<String, Object> args) {
        throw new UnsupportedOperationException("Provider " + name() + " does not handle tool " + toolName);
    }

    default void shutdown() {
    }
}
