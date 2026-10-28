package org.wwz.ai.application.agent.authorization;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingOwnershipPort;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话访问授权应用服务。
 */
@Service
@RequiredArgsConstructor
public class ConversationSessionAuthorizationService {

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;
    private final ObjectProvider<LtmManager> ltmManagerProvider;

    /**
     * IM 渠道绑定的 owner 判定。渠道模块未启用时容器里没有实现，保持原有「只认会话本人」行为。
     */
    @Autowired(required = false)
    private ChannelBindingOwnershipPort channelBindingOwnershipPort;

    /**
     * 交互入口：校验归属，且会话必须是可交互的用户会话。
     * <p>
     * 子智能体隐藏会话（{@code session_kind=subagent}）为只读，禁止提交新 query / 继续对话 / 上传附件。
     */
    public DialogueSession ensureSessionAccessible(String userId, String sessionId, String queryText) {
        return ensureSessionAccessible(userId, sessionId, queryText, true);
    }

    /**
     * 续跑入口：校验归属，且会话必须是可交互的用户会话。
     * <p>
     * 与 {@link #ensureExistingSessionAccessible} 的区别在于：本方法拒绝只读会话。
     * resume（计划审批 / 追问 / 桌面控制）属于交互行为，子会话不可参与。
     */
    public DialogueSession ensureInteractiveSessionAccessible(String userId, String sessionId) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        DialogueSession session = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (session == null) {
            throw new SessionOwnershipDeniedException("当前会话不存在");
        }
        assertInteractive(session);
        if (!StringUtils.equals(session.getUserId(), userId)) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return session;
    }

    /**
     * 只读访问：owner 可读取 history / stream，包含子智能体隐藏会话。
     * <p>
     * 不允许因为探测或详情请求自动创建空会话。
     */
    public DialogueSession ensureExistingSessionAccessible(String userId, String sessionId) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        DialogueSession existing = executionLedgerReadRepository.querySessionOwnership(sessionId);
        if (existing == null) {
            throw new SessionOwnershipDeniedException("当前会话不存在");
        }
        if (StringUtils.isBlank(existing.getUserId())) {
            DialogueSession fullSession = executionLedgerReadRepository.querySessionEntity(sessionId);
            if (fullSession == null) {
                throw new SessionOwnershipDeniedException("当前会话不存在");
            }
            executionLedgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(fullSession.getSessionId())
                    .userId(userId)
                    .title(fullSession.getTitle())
                    .status(fullSession.getStatus())
                    .latestRequestId(fullSession.getLatestRequestId())
                    .latestQueryText(fullSession.getLatestQueryText())
                    .latestSummaryText(fullSession.getLatestSummaryText())
                    .runCount(fullSession.getRunCount())
                    .finishedRunCount(fullSession.getFinishedRunCount())
                    .failedRunCount(fullSession.getFailedRunCount())
                    .startedAt(fullSession.getStartedAt())
                    .lastActiveAt(fullSession.getLastActiveAt())
                    .build());
            return executionLedgerWriteRepository.querySessionBySessionId(sessionId);
        }
        if (!StringUtils.equals(existing.getUserId(), userId)) {
            // IM 渠道会话：归属是绑定的所有者，而不是发消息的陌生人。
            if (isChannelOwner(existing, userId)) {
                return existing;
            }
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return existing;
    }

    /**
     * 该会话是否属于某个 IM 绑定，且当前用户正是该绑定的 owner。
     * <p>{@code binding_id} 为空的会话（Web UI 原生会话）恒返回 false，行为与改动前一致。</p>
     */
    private boolean isChannelOwner(DialogueSession session, String userId) {
        if (session == null || session.getBindingId() == null || StringUtils.isBlank(userId)) {
            return false;
        }
        ChannelBindingOwnershipPort port = channelBindingOwnershipPort;
        if (port == null) {
            return false;
        }
        return port.isOwner(session.getBindingId(), userId);
    }

    private DialogueSession ensureSessionAccessible(String userId,
                                                   String sessionId,
                                                   String queryText,
                                                   boolean allowBindWhenMissing) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        DialogueSession existing = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (existing == null) {
            if (!allowBindWhenMissing) {
                throw new SessionOwnershipDeniedException("当前会话不存在");
            }
            LocalDateTime now = LocalDateTime.now();
            executionLedgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .rootSessionId(sessionId)
                    .title(resolveSessionTitle(queryText))
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .runCount(0)
                    .finishedRunCount(0)
                    .failedRunCount(0)
                    .startedAt(now)
                    .lastActiveAt(now)
                    .build());
            // 新会话：LTM 边界 end→switch（用户级 curated 不清空）
            notifyLtmNewSession(sessionId);
            return executionLedgerWriteRepository.querySessionBySessionId(sessionId);
        }
        // 只读会话（子智能体隐藏会话）不得作为交互会话继续使用
        assertInteractive(existing);
        if (StringUtils.isBlank(existing.getUserId())) {
            executionLedgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(existing.getSessionId())
                    .userId(userId)
                    .title(StringUtils.defaultIfBlank(existing.getTitle(), resolveSessionTitle(queryText)))
                    .status(existing.getStatus())
                    .latestRequestId(existing.getLatestRequestId())
                    .latestQueryText(existing.getLatestQueryText())
                    .latestSummaryText(existing.getLatestSummaryText())
                    .runCount(existing.getRunCount())
                    .finishedRunCount(existing.getFinishedRunCount())
                    .failedRunCount(existing.getFailedRunCount())
                    .startedAt(existing.getStartedAt())
                    .lastActiveAt(existing.getLastActiveAt())
                    .build());
            return executionLedgerWriteRepository.querySessionBySessionId(sessionId);
        }
        if (!StringUtils.equals(existing.getUserId(), userId)) {
            // 交互路径刻意不放行 owner：owner 对自己的 bot 只有读权限，
            // 否则他就能往陌生人的 QQ 会话里续写，伪装成对方。
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return existing;
    }

    /**
     * 断言会话可交互。未知/缺失类型按 fail-closed 处理（见 {@link SessionKind#fromCode}）。
     * <p>
     * 子会话与主会话同构（独立 run / 记忆 / 观察流），用户可以从会话树点进去继续对话，
     * 因此 {@link SessionKind#SUBAGENT} 一并放行。
     */
    private static void assertInteractive(DialogueSession session) {
        if (session == null) {
            return;
        }
        SessionKind kind = SessionKind.fromCode(session.getSessionKind());
        if (kind != SessionKind.CONVERSATION && kind != SessionKind.SUBAGENT) {
            throw new SessionNotInteractiveException(
                    "该会话为只读会话（" + kind.code() + "），不支持继续对话或续跑");
        }
    }

    /**
     * 对话标题与账本 recorder 保持同一套收口逻辑，避免首次绑定与首次 run 的标题规则漂移。
     */
    private String resolveSessionTitle(String queryText) {
        String normalized = StringUtils.trimToEmpty(queryText);
        if (normalized.isEmpty()) {
            return "新对话";
        }
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private void notifyLtmNewSession(String newSessionId) {
        LtmManager ltmManager = ltmManagerProvider == null ? null : ltmManagerProvider.getIfAvailable();
        if (ltmManager == null || StringUtils.isBlank(newSessionId)) {
            return;
        }
        try {
            ltmManager.commitSessionBoundaryAsync(List.of(), newSessionId, "", true);
        } catch (Exception ignored) {
            // LTM 边界失败不阻断会话创建
        }
    }
}
