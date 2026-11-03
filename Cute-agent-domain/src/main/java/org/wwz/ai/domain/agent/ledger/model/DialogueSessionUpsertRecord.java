package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话主表 upsert 命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueSessionUpsertRecord {

    private String sessionId;

    private String userId;

    /**
     * 所属 IM 渠道绑定。只在建行时写入，后续 upsert 不覆盖（与 session_kind 同策略）。
     */
    private Long bindingId;

    /** IM 出站会话形态；null = 未记录，upsert 时保留库里已有值。 */
    private String channelChatType;

    /** IM 出站目标 ID；null = 未记录，upsert 时保留库里已有值。 */
    private String channelTargetId;

    /**
     * 会话类型（落库为 code），调用方应传
     * {@link org.wwz.ai.domain.agent.ledger.model.SessionKind#code()}。
     * 为空时由 SQL 默认值兜底为 {@code conversation}；非法 code 不会被写入校验拦截，
     * 只会在读取侧被 {@code SessionKind} 的白名单过滤。
     */
    private String sessionKind;

    /** 父会话ID，仅审计与授权穿透；子会话不继承父工作记忆。 */
    private String parentSessionId;

    /**
     * 根会话ID：根会话指向自己，子会话指向树根。
     * <p>与 {@link #parentSessionId} 同属身份列，只在建行时写入，ON DUPLICATE 段不覆盖。</p>
     */
    private String rootSessionId;

    /**
     * 子智能体类型，决定该会话的工具池。
     * <p>与 {@link #parentSessionId} 同属身份列，只在建行时写入。</p>
     */
    private String subAgentType;

    private String title;

    private Integer status;

    private String latestRequestId;

    private String latestQueryText;

    private String latestSummaryText;

    /**
     * 以下三个计数列由 upsert 路径决定归属：
     * {@code upsertSessionOnRunStart} 在 SQL 内原子累加 run_count；
     * {@code updateSessionRunFinish} 原子累加 finished/failed；
     * {@code upsertSession} 则整行覆盖。run-start 路径不应填写 finished/failed 计数。
     * 为空表示「不指定」。
     */
    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    /**
     * 会话级模型标识。
     * <p>与 {@code session_kind} 相反，这四列<b>要</b>进 ON DUPLICATE 段——用户切换模型时
     * 必须能更新。{@code null} 表示「本轮未显式选择」，SQL 用 COALESCE 保留库里已有的值。</p>
     */
    private String model;

    /** 是否开启深度思考；{@code null} = 未显式选择，不清空已有值。 */
    private Boolean thinking;

    /** 思考强度；{@code null} = 未显式选择，不清空已有值。 */
    private String thinkingEffort;

    /** Deep Think 开关。NOT NULL 且前端永远传，直接覆盖，不走 COALESCE。 */
    private Integer deepThink;
}
