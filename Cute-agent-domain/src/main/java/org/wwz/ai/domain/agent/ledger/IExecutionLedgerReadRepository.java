package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.RunCursor;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 执行账本读仓储端口。
 * 仅暴露查询服务当前需要的聚合读能力。
 *
 * <p><b>归属要求</b>：本端口同时提供 {@code userId} 过滤与「只按 sessionId/requestId」的
 * 两组读方法。后者不做归属校验，<b>调用方必须已经通过
 * {@link #querySessionOwnership(String)}（或上层授权服务）确认归属</b>后才能使用；
 * 面向跨用户入口的新代码应优先使用带 userId 的变体，或先做同等的 ownership 校验。</p>
 */
public interface IExecutionLedgerReadRepository {

    /**
     * 批量按 runId 查询的最大 id 数量，与 {@link ExecutionLedgerQueryService#MAX_SESSION_RUN_PAGE_SIZE} 对齐，
     * 保证一次分页查询的 run 集合可以用单条 {@code IN (...)} 下推。
     */
    int MAX_BATCH_RUN_IDS = 100;

    /** 单会话产物查询的默认安全上限。 */
    int DEFAULT_SESSION_ARTIFACT_LIMIT = 500;

    DialogueRun queryRunByRequestId(String requestId);

    DialogueRun queryRunSummaryByRequestId(String requestId);

    List<LlmInvocation> queryLlmInvocationsByRunId(Long runId);

    /**
     * 按 runId 批量查询 LLM 调用。
     * <p>契约：{@code runIds} 为 {@code null} 或空时返回空列表，绝不返回 {@code null}；
     * 调用方一次最多传入 {@link #MAX_BATCH_RUN_IDS} 个 id。实现必须用单条 {@code IN} 查询，
     * 不得退化为按 id 逐个查询。</p>
     */
    List<LlmInvocation> queryLlmInvocationsByRunIds(List<Long> runIds);

    List<ToolInvocation> queryToolInvocationsByRunId(Long runId);

    /**
     * 按 runId 批量查询工具调用。契约同 {@link #queryLlmInvocationsByRunIds(List)}。
     */
    List<ToolInvocation> queryToolInvocationsByRunIds(List<Long> runIds);

    List<ArtifactRecord> queryArtifactsByRunId(Long runId);

    /**
     * 查询父 run 自身产物，并将跨 run 的子 Agent 产物按血缘重键到父 Agent 卡。
     */
    List<ArtifactRecord> queryArtifactsByRunIdWithSubagentFold(Long runId);

    /**
     * 按用户 + 工具名读取最近调用，倒序（{@code create_time DESC, id DESC}）。
     * <p>实现必须把 {@code user_id} 条件下推到 SQL；{@code userId} 为空时返回空列表。</p>
     */
    List<ToolInvocationView> queryRecentToolInvocations(String userId, String toolName, int limit);

    List<DialogueRunView> queryRecentRunsBySessionId(String sessionId, int limit);

    /**
     * offset 分页读取 session run。
     * <p>实现必须自行收敛参数：{@code offset < 0} 按 0 处理，{@code limit <= 0} 取默认值，
     * 且 {@code limit} 不超过最大页大小（当前实现为 100），不得把调用方传入的
     * 任意大 limit 原样下推到 SQL。</p>
     */
    List<DialogueRunView> queryRunsBySessionId(String sessionId, int offset, int limit);

    /**
     * 按 create_time/id 正序读取 session run，cursor 为空时从头开始。
     * 实现层不得为该轻量查询补 artifact 或 rich tool output。
     */
    List<DialogueRunView> queryRunsBySessionId(String sessionId, RunCursor after, int limit);

    /** 兼容已有内部调用方；底层查询仍受分页上限约束。 */
    default List<DialogueRunView> queryRunsBySessionId(String sessionId) {
        return queryRunsBySessionId(sessionId, 0, 100);
    }

    DialogueSessionView querySession(String sessionId);

    DialogueSessionView querySessionHistorySummary(String sessionId);

    List<DialogueSessionView> queryRecentSessions(int limit);

    /** 读取会话完整实体（含计数、标题、模型偏好等全部列），用于偏好/上下文等全量读取。 */
    DialogueSession querySessionEntity(String sessionId);

    /**
     * 只读取归属判定所需的最小列：{@code id / sessionId / userId / bindingId / sessionKind / deleted}。
     * <p>其余字段为 {@code null}，调用方不得依赖；仅用于授权校验等不需要完整实体的场景。</p>
     */
    DialogueSession querySessionOwnership(String sessionId);

    DialogueSessionView querySession(String userId, String sessionId);

    List<DialogueSessionView> queryRecentSessions(String userId, int limit);

    /**
     * 按 IM 渠道绑定查会话，供 owner 查看自己 bot 下的对话。
     * <p>只返回 {@code session_kind=conversation} 的会话，与侧栏白名单一致。</p>
     */
    List<DialogueSessionView> querySessionsByBindingId(Long bindingId, int limit);

    /**
     * 按树根查整棵会话树：根会话自身 + 其下所有子会话。
     * <p>根会话的 {@code root_session_id} 指向自己，一条等值查询即可覆盖全树。</p>
     */
    List<DialogueSessionView> querySessionsByRootId(String rootSessionId, int limit);

    List<DialogueSessionView> queryRecentSessions(String userId, ConversationSessionCursor after, int limit);

    /**
     * 按 runId 批量查询产物。契约同 {@link #queryLlmInvocationsByRunIds(List)}。
     */
    List<ArtifactRecord> queryArtifactsByRunIds(List<Long> runIds);

    /**
     * 批量查询父 runs 的产物，并将跨 run 的子 Agent 产物按血缘重键到父 Agent 卡。
     * <p>契约同 {@link #queryLlmInvocationsByRunIds(List)}。</p>
     */
    List<ArtifactRecord> queryArtifactsByRunIdsWithSubagentFold(List<Long> runIds);

    /**
     * 单会话产物查询的默认入口；等价于
     * {@code queryArtifactsBySessionId(sessionId, DEFAULT_SESSION_ARTIFACT_LIMIT)}。
     */
    default List<ArtifactRecord> queryArtifactsBySessionId(String sessionId) {
        return queryArtifactsBySessionId(sessionId, DEFAULT_SESSION_ARTIFACT_LIMIT);
    }

    /**
     * 查询单会话（含子会话与子 Agent 血缘）产物，最多 {@code limit} 行。
     * <p>实现必须把 LIMIT 下推到 SQL；{@code limit <= 0} 取默认上限，且不得超过
     * {@link #DEFAULT_SESSION_ARTIFACT_LIMIT}。命中上限时应记录日志，提示调用方改用分页/按 run 查询。</p>
     */
    List<ArtifactRecord> queryArtifactsBySessionId(String sessionId, int limit);

    /**
     * 批量读取每个 session 的首个非空 run query，用于恢复会话展示标题。
     * <p>契约同 {@link #queryLlmInvocationsByRunIds(List)}：{@code null}/空入参返回空 Map，
     * 返回值 key 为 sessionId，value 为最早一条非空 query；实现必须用单条查询完成，
     * 不得按 session 逐个查询。</p>
     */
    Map<String, String> queryFirstNonBlankQueriesBySessionIds(List<String> sessionIds);
}
