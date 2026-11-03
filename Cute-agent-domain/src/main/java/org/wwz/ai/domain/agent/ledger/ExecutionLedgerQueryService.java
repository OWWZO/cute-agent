package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionPage;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;

import java.util.List;

/**
 * 执行账本内部查询契约。
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
    /** 单次会话 artifact 查询的硬上限，避免长会话把整张产物表读进内存。 */
    int MAX_SESSION_ARTIFACTS = 500;

    ExecutionRunDetail queryRunDetail(String requestId);

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

    /** 只读取 run 主表字段，用于 ownership 校验和轻量摘要。 */
    DialogueRunView queryRunSummary(String requestId);

    /**
     * 批量加载已经定位好的 run 明细，避免历史回放按 run 重复查询账本事实。
     * <p>返回列表与入参 {@code runs} 顺序、数量一一对应；{@code null} 元素返回空明细。
     * 入参为 {@code null} 或空时返回空列表。</p>
     */
    List<ExecutionRunDetail> queryRunDetails(List<DialogueRunView> runs);

    List<ToolInvocationView> queryRecentToolInvocations(String toolName, int limit);

    List<DialogueRunView> queryRecentSessionRuns(String sessionId, int limit);

    /** 按创建时间正序读取 session run 页面，limit 会在实现层强制限制。 */
    List<DialogueRunView> querySessionRuns(String sessionId, int offset, int limit);

    /** 按 create_time/id 正序读取 keyset 页面，返回数量可能为 limit+1。 */
    List<DialogueRunView> querySessionRuns(String sessionId, RunCursor after, int limit);

    /** 兼容已有调用方的首页查询；完整历史由 replay/memory 调用方逐页读取。 */
    default List<DialogueRunView> querySessionRuns(String sessionId) {
        return querySessionRuns(sessionId, 0, DEFAULT_SESSION_RUN_PAGE_SIZE);
    }

    DialogueSessionView querySession(String sessionId);

    /** 只读取会话元数据和截断后的最新摘要，供 UI 历史摘要页使用。 */
    DialogueSessionView querySessionHistorySummary(String sessionId);

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
}
