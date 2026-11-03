package org.wwz.ai.domain.agent.ledger.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 会话类型，决定会话在哪些入口可见。
 * <p>
 * 对齐 ZCode 的 {@code taskType}：可见性由<b>类型白名单</b>决定，
 * 而不是 {@code parent_session_id is null} 这类层级查询 —— 后者会把"有父但应当在列表中"的会话误杀，
 * 也会让"有父但必须隐藏"的子会话漏出来。
 *
 * @see #isListable()
 */
public enum SessionKind {

    /** 用户会话：侧栏列表可见，可继续对话。 */
    CONVERSATION("conversation"),

    /**
     * 子智能体会话：与主会话同构（独立 run / 记忆 / 观察流），可被用户续聊。
     * <p>
     * 血缘由 {@code parent_session_id} 表达；「可续聊」由 case 层的
     * {@code ConversationSessionAuthorizationService#assertInteractive} 放行，
     * 与「是否进侧栏列表」是两件事——后者等前端会话树 UI 落地时再放开
     * （见 {@code 2026-10-26-subagent-first-class-session-plan.md} §7.4）。
     */
    SUBAGENT("subagent"),

    /**
     * 未知或缺失类型：一律 fail-closed —— 既不进侧栏列表，也不作为可交互会话放行
     * （由 case 层 {@code ConversationSessionAuthorizationService#assertInteractive} 拒绝）。
     * <p>
     * 之所以不回落 {@link #CONVERSATION}：新增类型（如未来的 {@code subagent_v2}）若被误判成普通会话，
     * 会直接绕过列表过滤与只读闸门，属于权限 fail-open。
     */
    UNKNOWN("unknown");

    private final String code;

    SessionKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 是否出现在侧栏会话列表。只有用户会话进列表。
     * <p>
     * 子会话暂时不进列表：前端会话树 UI 尚未落地，提前放开只会让子会话以扁平条目
     * 混进侧栏，缺少父会话上下文。与 SQL（{@code dialogue_session_ledger_mapper.xml} 中的
     * {@code AND session_kind = 'conversation'}）保持一致，两处必须同改。
     */
    public boolean isListable() {
        return this == CONVERSATION;
    }

    /**
     * 是否为子智能体会话。
     */
    public boolean isSubAgent() {
        return this == SUBAGENT;
    }

    /**
     * 解析持久化值。未知值、空值一律返回 {@link #UNKNOWN}（fail-closed），
     * 绝不回落为 {@link #CONVERSATION}。
     */
    public static SessionKind fromCode(String value) {
        if (StringUtils.isBlank(value)) {
            return UNKNOWN;
        }
        String normalized = value.trim();
        for (SessionKind kind : values()) {
            if (kind.code.equalsIgnoreCase(normalized)) {
                return kind;
            }
        }
        return UNKNOWN;
    }

    /**
     * 便捷判定：给定持久化值是否可进侧栏列表。未知值返回 {@code false}。
     */
    public static boolean isListableCode(String value) {
        return fromCode(value).isListable();
    }
}
