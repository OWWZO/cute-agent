package org.wwz.ai.domain.agent.ledger.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.time.LocalDateTime;

/**
 * 会话级执行摘要。
 * <p>
 * 充血模型：所有者绑定、标题变更与 run 计数转移由实体自身承载，并强制以下不变量：
 * <ul>
 *     <li>已绑定会话不能被其他用户覆盖所有者；</li>
 *     <li>runCount / finishedRunCount / failedRunCount 均不得为负数；</li>
 *     <li>finishedRunCount 不得超过 runCount；</li>
 *     <li>failedRunCount 不得超过 finishedRunCount。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueSession {

    private Long id;

    private String sessionId;

    private String userId;

    /**
     * 所属 IM 渠道绑定；{@code null} 表示 Web UI 原生会话。
     * <p>非空时该会话的读权限除了本人，还对该绑定 owner 开放。</p>
     */
    private Long bindingId;

    /**
     * 会话类型 code，见 {@link org.wwz.ai.domain.agent.ledger.model.SessionKind}。
     */
    private String sessionKind;

    /** 父会话ID，仅审计与授权穿透。 */
    private String parentSessionId;

    /**
     * 根会话ID：根会话指向自己，子会话指向树根。
     * <p>{@code parent_session_id} 是权威血缘链，本列是它的缓存——用于一次查询取整棵会话树。
     * 将来开放多层嵌套时不必再改 schema。</p>
     */
    private String rootSessionId;

    /**
     * 子智能体类型（对应 {@code ai_agent_sub_agent_definition.agent_key}），决定该会话的工具池。
     * <p>身份列，只在建行时写入；{@code null} 表示未记录类型，装配时回退全量工具池。</p>
     */
    private String subAgentType;

    private String title;

    private Integer status;

    private String latestRequestId;

    private String latestQueryText;

    private String latestSummaryText;

    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    /**
     * 会话级模型标识；{@code null} 表示用户从未显式选择过。
     * <p>子会话在父 Agent 结束、内存上下文被回收后，靠这一列重建自己的运行时选择。</p>
     */
    private String model;

    /** 是否开启深度思考；{@code null} 表示未显式选择。 */
    private Boolean thinking;

    /** 思考强度（low/medium/high）；{@code null} 表示用默认。 */
    private String thinkingEffort;

    /** Deep Think 开关；派生 {@code agentType}（1 → plan_solve，0 → react）。 */
    private Integer deepThink;

    private Long eventSeq;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;

    /**
     * 绑定所有者。已绑定会话不能被其他用户覆盖。
     */
    public void bindOwner(String userId) {
        if (StringUtils.isBlank(userId)) {
            return;
        }
        if (StringUtils.isNotBlank(this.userId) && !this.userId.equals(userId)) {
            throw new IllegalStateException("会话已绑定其他用户，不能覆盖所有者: sessionId=" + sessionId);
        }
        this.userId = userId;
    }

    /**
     * 重命名会话。空白标题视为无效输入，保持原值。
     */
    public void rename(String title) {
        if (StringUtils.isNotBlank(title)) {
            this.title = title;
        }
    }

    /**
     * 记录一次新的 run 开始：runCount 自增，并刷新最新活动信息。
     */
    public void startRun(String requestId, String queryText, LocalDateTime now) {
        int runs = normalizeCount(this.runCount) + 1;
        int finished = normalizeCount(this.finishedRunCount);
        int failed = normalizeCount(this.failedRunCount);
        validateInvariants(runs, finished, failed);
        this.runCount = runs;
        this.finishedRunCount = finished;
        this.failedRunCount = failed;
        this.latestRequestId = requestId;
        this.latestQueryText = queryText;
        this.status = ExecutionLedgerConstants.STATUS_RUNNING;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.lastActiveAt = now;
    }

    /**
     * 记录一次 run 结束：finishedRunCount 自增，并刷新终态与最新摘要。
     */
    public void finishRun(Integer status, String summary, LocalDateTime now) {
        int runs = normalizeCount(this.runCount);
        int finished = normalizeCount(this.finishedRunCount) + 1;
        int failed = normalizeCount(this.failedRunCount);
        validateInvariants(runs, finished, failed);
        this.runCount = runs;
        this.finishedRunCount = finished;
        this.failedRunCount = failed;
        this.status = status;
        if (summary != null) {
            this.latestSummaryText = summary;
        }
        this.lastActiveAt = now;
    }

    /**
     * 记录一次失败 run。必须在对应 run 已计入 finishedRunCount 之后调用，否则违反不变量。
     */
    public void recordFailedRun(LocalDateTime now) {
        int runs = normalizeCount(this.runCount);
        int finished = normalizeCount(this.finishedRunCount);
        int failed = normalizeCount(this.failedRunCount) + 1;
        validateInvariants(runs, finished, failed);
        this.runCount = runs;
        this.finishedRunCount = finished;
        this.failedRunCount = failed;
        this.lastActiveAt = now;
    }

    /**
     * 校验会话计数不变量。任何一次状态转移后都必须成立。
     */
    public void assertInvariants() {
        validateInvariants(
                normalizeCount(this.runCount),
                normalizeCount(this.finishedRunCount),
                normalizeCount(this.failedRunCount)
        );
    }

    private void validateInvariants(int runs, int finished, int failed) {
        if (runs < 0) {
            throw new IllegalStateException("runCount 不得为负数: " + runs);
        }
        if (finished < 0) {
            throw new IllegalStateException("finishedRunCount 不得为负数: " + finished);
        }
        if (failed < 0) {
            throw new IllegalStateException("failedRunCount 不得为负数: " + failed);
        }
        if (finished > runs) {
            throw new IllegalStateException("finishedRunCount 不得超过 runCount: finished=" + finished + ", runs=" + runs);
        }
        if (failed > finished) {
            throw new IllegalStateException("failedRunCount 不得超过 finishedRunCount: failed=" + failed + ", finished=" + finished);
        }
    }

    private int normalizeCount(Integer value) {
        return value == null ? 0 : value;
    }
}
