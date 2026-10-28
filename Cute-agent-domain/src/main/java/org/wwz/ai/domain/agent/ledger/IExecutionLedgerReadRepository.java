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

/**
 * Phase 1 执行账本读仓储端口。
 * 仅暴露查询服务当前需要的聚合读能力。
 */
public interface IExecutionLedgerReadRepository {

    DialogueRun queryRunByRequestId(String requestId);

    DialogueRun queryRunSummaryByRequestId(String requestId);

    List<LlmInvocation> queryLlmInvocationsByRunId(Long runId);

    List<LlmInvocation> queryLlmInvocationsByRunIds(List<Long> runIds);

    List<ToolInvocation> queryToolInvocationsByRunId(Long runId);

    List<ToolInvocation> queryToolInvocationsByRunIds(List<Long> runIds);

    List<ArtifactRecord> queryArtifactsByRunId(Long runId);

    /**
     * 查询父 run 自身产物，并将跨 run 的子 Agent 产物按血缘重键到父 Agent 卡。
     */
    List<ArtifactRecord> queryArtifactsByRunIdWithSubagentFold(Long runId);

    List<ToolInvocationView> queryRecentToolInvocations(String toolName, int limit);

    List<DialogueRunView> queryRecentRunsBySessionId(String sessionId, int limit);

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

    DialogueSession querySessionEntity(String sessionId);

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

    List<ArtifactRecord> queryArtifactsByRunIds(List<Long> runIds);

    /**
     * 批量查询父 runs 的产物，并将跨 run 的子 Agent 产物按血缘重键到父 Agent 卡。
     */
    List<ArtifactRecord> queryArtifactsByRunIdsWithSubagentFold(List<Long> runIds);

    List<ArtifactRecord> queryArtifactsBySessionId(String sessionId);
}
