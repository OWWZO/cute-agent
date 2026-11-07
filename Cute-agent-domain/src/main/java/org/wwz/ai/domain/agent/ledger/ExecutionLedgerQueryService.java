package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionPage;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行账本内部查询契约。
 *
 * <p><b>归属要求</b>：带 {@code userId} 的重载会在实现层校验会话归属
 * （会话本人，或该会话绑定的 IM 渠道 owner），不匹配时返回 {@code null}/空列表。
 * 不带 {@code userId} 的重载<b>仅供已在上层完成归属校验的内部调用方</b>使用
 * （如 trigger 先调用 {@code ConversationSessionAuthorizationService}），
 * 新增调用方必须先完成同等校验，不能仅凭 sessionId/requestId 直接读取。</p>
 */
public interface ExecutionLedgerQueryService {

    /** 会话 run 分页的默认页大小，必须严格小于 {@link #MAX_SESSION_RUN_PAGE_SIZE}。 */
    int DEFAULT_SESSION_RUN_PAGE_SIZE = 50;
    /** 会话 run 分页的硬上限。 */
    int MAX_SESSION_RUN_PAGE_SIZE = 100;
    int DEFAULT_RECENT_SESSION_PAGE_SIZE = 10;
    int MAX_RECENT_SESSION_PAGE_SIZE = 100;
    /** 会话树查询返回节点数的硬上限。 */
    int MAX_SESSION_TREE_SIZE = 100;
    /** 最近工具调用查询的硬上限，与其它台账分页保持一致。 */
    int MAX_RECENT_TOOL_INVOCATION_PAGE_SIZE = 100;
    /** 单次会话 artifact 查询的硬上限，避免长会话把整张产物表读进内存。 */
    int MAX_SESSION_ARTIFACTS = 500;

    /** 已授权调用方使用：调用方必须先完成归属校验。 */
    ExecutionRunDetail queryRunDetail(String requestId);

    /** 归属校验通过后返回 run 详情；非 owner 返回 {@code null}。 */
    ExecutionRunDetail queryRunDetail(String userId, String requestId);

    /**
     * 只读取当前会话可见的输入/输出 artifact，不展开 run 回放明细。
     * <p>等价于 {@link #querySessionArtifacts(String, int)} 并取默认上限
     * {@link #MAX_SESSION_ARTIFACTS}。</p>
     */
    default List<ArtifactView> querySessionArtifacts(String sessionId) {
        return querySessionArtifacts(sessionId, MAX_SESSION_ARTIFACTS);
    }

    /**
     * 只读取当前会话可见的输入/输出 artifact，并强制截断到 {@code limit} 行。
     * <p>{@code limit <= 0} 取默认值，任何情况下不超过 {@link #MAX_SESSION_ARTIFACTS}；
     * “可见”指 {@code visibility=visible}，实现层负责过滤。</p>
     */
    List<ArtifactView> querySessionArtifacts(String sessionId, int limit);

    /** 归属校验通过后返回会话产物；非 owner 返回空列表。 */
    List<ArtifactView> querySessionArtifacts(String userId, String sessionId);

    /** 只读取 run 主表字段，用于 ownership 校验和轻量摘要。 */
    DialogueRunView queryRunSummary(String requestId);

    /** 归属校验通过后返回 run 摘要；非 owner 返回 {@code null}。 */
    DialogueRunView queryRunSummary(String userId, String requestId);

    /**
     * 批量加载已经定位好的 run 明细，避免历史回放按 run 重复查询账本事实。
     * <p>返回列表与入参 {@code runs} 顺序、数量一一对应；{@code null} 元素返回空明细。
     * 入参为 {@code null} 或空时返回空列表。</p>
     */
    List<ExecutionRunDetail> queryRunDetails(List<DialogueRunView> runs);

    /**
     * 按工具名查询当前用户最近的调用（倒序：{@code create_time DESC, id DESC}）。
     * <p>{@code limit <= 0} 取默认值，任何情况下不超过
     * {@link #MAX_RECENT_TOOL_INVOCATION_PAGE_SIZE}；{@code userId} 为空时返回空列表，
     * 实现必须把用户条件下推到 SQL，不能返回全量用户数据。</p>
     */
    List<ToolInvocationView> queryRecentToolInvocations(String userId, String toolName, int limit);

    List<DialogueRunView> queryRecentSessionRuns(String sessionId, int limit);

    /** 归属校验通过后返回最近 run；非 owner 返回空列表。 */
    List<DialogueRunView> queryRecentSessionRuns(String userId, String sessionId, int limit);

    /** 按创建时间正序读取 session run 页面，limit 会在实现层强制限制。 */
    List<DialogueRunView> querySessionRuns(String sessionId, int offset, int limit);

    /**
     * 按 run 创建时间窗口读取轻量摘要，供运维/测试专用只读导出使用。
     *
     * <p>时间范围左闭右开：{@code [from, to)}。返回项不附加 artifact 摘要，
     * 调用方需要完整事实时应继续调用 {@link #queryRunDetails(List)}。</p>
     * <p><b>安全边界：</b>这是跨会话、跨用户的全局读取，仅供运维/测试工具使用，
     * 禁止直接暴露到 HTTP、触发器或任何用户可调用入口；面向用户的 API 必须使用带
     * {@code userId}/{@code sessionId} 过滤并完成归属校验的重载。</p>
     */
    List<DialogueRunView> queryRunsByTimeRange(LocalDateTime from, LocalDateTime to, int limit);

    /**
     * 运维/测试专用 keyset 分页；时间范围左闭右开，状态为空表示不筛状态。
     */
    List<DialogueRunView> queryRunsByTimeRange(
            LocalDateTime from,
            LocalDateTime to,
            List<Integer> statuses,
            LocalDateTime afterCreateTime,
            Long afterId,
            int limit);

    /** 按 create_time/id 正序读取 keyset 页面，返回数量可能为 limit+1。 */
    List<DialogueRunView> querySessionRuns(String sessionId, RunCursor after, int limit);

    /** 兼容已有调用方的首页查询；完整历史由 replay/memory 调用方逐页读取。 */
    default List<DialogueRunView> querySessionRuns(String sessionId) {
        return querySessionRuns(sessionId, 0, DEFAULT_SESSION_RUN_PAGE_SIZE);
    }

    DialogueSessionView querySession(String sessionId);

    /** 只读取会话元数据和截断后的最新摘要，供 UI 历史摘要页使用。 */
    DialogueSessionView querySessionHistorySummary(String sessionId);

    /** 归属校验通过后返回会话摘要；非 owner 返回 {@code null}。 */
    DialogueSessionView querySessionHistorySummary(String userId, String sessionId);

    List<DialogueSessionView> queryRecentSessions(int limit);

    DialogueSessionView querySession(String userId, String sessionId);

    List<DialogueSessionView> queryRecentSessions(String userId, int limit);

    ConversationSessionPage queryRecentSessions(String userId, ConversationSessionCursor after, int limit);

    /**
     * 查整棵会话树：给定树中任一节点，返回「根 + 全部后代」的扁平列表。
     * <p>层级由 {@code rootSessionId} 表达，调用方自行组树；列表内根会话在前。</p>
     * <p>{@code limit} 限制的是<b>本次返回的总节点数</b>（不是每层子节点数，也不是深度）：
     * 超过上限时只返回部分树（根优先），调用方无法从返回值判断是否被截断。
     * {@code limit <= 0} 时实现取默认值，任何情况下不超过 {@link #MAX_SESSION_TREE_SIZE}。
     * 树的根由 {@code rootSessionId} 等值查询确定，不做父链遍历，
     * 因此畸形/成环的 {@code parentSessionId} 不会导致无界遍历。</p>
     */
    List<DialogueSessionView> querySessionTree(String sessionId, int limit);

    /** 归属校验通过后返回会话树；非 owner 返回空列表。 */
    List<DialogueSessionView> querySessionTree(String userId, String sessionId, int limit);
}
