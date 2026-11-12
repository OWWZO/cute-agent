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
     * IM 渠道绑定的 owner 判定。渠道模块未启用时容器里没有实现，
     * 通过 {@link ObjectProvider} 显式回退到 {@link ChannelBindingOwnershipPort#deniesAll()}，
     * 保持原有「只认会话本人」行为，而不是依赖缺失 bean 的隐性注入失败。
     */
    @Autowired
    private ObjectProvider<ChannelBindingOwnershipPort> channelBindingOwnershipPortProvider;

    /**
     * 交互入口：校验归属，且会话必须是可交互的用户会话。
     * <p>
     * {@link SessionKind#CONVERSATION} 与 {@link SessionKind#SUBAGENT} 均可交互；其他或未知类型
     * 按只读会话拒绝。
     */
    public DialogueSession ensureSessionAccessible(String userId, String sessionId, String queryText) {
        return ensureSessionAccessibleAndBind(userId, sessionId, queryText);
    }

    /**
     * 续跑入口：校验归属，且会话必须是可交互的用户会话。
     * <p>
     * 与 {@link #ensureExistingSessionAccessible} 的区别在于：本方法会校验会话类型。
     * {@link SessionKind#CONVERSATION} 与 {@link SessionKind#SUBAGENT} 均可续跑，其他或未知类型
     * 按只读会话拒绝。
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
        if (!canAccessInteractive(session, userId)) {
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
            executionLedgerWriteRepository.upsertSession(
                    buildOwnedSessionSnapshot(fullSession, userId, fullSession.getTitle()));
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
        ChannelBindingOwnershipPort port = channelBindingOwnershipPortProvider == null
                ? ChannelBindingOwnershipPort.deniesAll()
                : channelBindingOwnershipPortProvider.getIfAvailable(ChannelBindingOwnershipPort::deniesAll);
        return port.isOwner(session.getBindingId(), userId);
    }

    private DialogueSession ensureSessionAccessibleAndBind(String userId,
                                                           String sessionId,
                                                           String queryText) {
        if (StringUtils.isAnyBlank(userId, sessionId)) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        DialogueSession existing = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (existing == null) {
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
            executionLedgerWriteRepository.upsertSession(buildOwnedSessionSnapshot(
                    existing,
                    userId,
                    StringUtils.defaultIfBlank(existing.getTitle(), resolveSessionTitle(queryText))));
            return executionLedgerWriteRepository.querySessionBySessionId(sessionId);
        }
        if (!canAccessInteractive(existing, userId)) {
            // 渠道会话放行 owner：这是他自己绑定的 bot，且他在 Web 端能看到全部内容，
            // 允许在这里继续对话，等同于换一个入口说同一段话。
            // 非 owner 仍被拒绝，无法往陌生人的 QQ 会话里续写。
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return existing;
    }

    /**
     * 代聊时的执行身份：owner 继续渠道会话时，run 必须用会话原本的 userId 执行，
     * 否则长期记忆会串到 owner 名下。
     */
    public String resolveRunUserId(String currentUserId, DialogueSession session) {
        if (session == null || session.getBindingId() == null) {
            return currentUserId;
        }
        if (StringUtils.equals(session.getUserId(), currentUserId)) {
            return currentUserId;
        }
        return isChannelOwner(session, currentUserId) ? session.getUserId() : currentUserId;
    }

    /**
     * 本人或渠道绑定 owner 均可交互。{@code binding_id} 为空的普通会话行为不变。
     */
    private boolean canAccessInteractive(DialogueSession session, String userId) {
        return StringUtils.equals(session.getUserId(), userId) || isChannelOwner(session, userId);
    }

    /**
     * 断言非 null 会话可交互。未知/缺失类型按 fail-closed 处理（见 {@link SessionKind#fromCode}）。
     * <p>
     * 子会话与主会话同构（独立 run / 记忆 / 观察流），用户可以从会话树点进去继续对话，
     * 因此 {@link SessionKind#SUBAGENT} 一并放行。
     * <p>
     * 调用方负责先确认会话存在；本方法对 null 直接返回，不能作为会话存在性的校验。
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

    /**
     * upsertSession 是整行写入：认领无主会话时必须携带现有身份列和偏好列，
     * 不能依赖 SQL 对部分列做兜底。尤其 deep_think 会被 VALUES 直接覆盖。
     */
    private static DialogueSessionUpsertRecord buildOwnedSessionSnapshot(
            DialogueSession session,
            String userId,
            String title) {
        return DialogueSessionUpsertRecord.builder()
                .sessionId(session.getSessionId())
                .userId(userId)
                .bindingId(session.getBindingId())
                .channelChatType(session.getChannelChatType())
                .channelTargetId(session.getChannelTargetId())
                .channelLastMsgId(session.getChannelLastMsgId())
                .channelLastReceivedAt(session.getChannelLastReceivedAt())
                .sessionKind(session.getSessionKind())
                .parentSessionId(session.getParentSessionId())
                .rootSessionId(session.getRootSessionId())
                .subAgentType(session.getSubAgentType())
                .title(title)
                .status(session.getStatus())
                .latestRequestId(session.getLatestRequestId())
                .latestQueryText(session.getLatestQueryText())
                .latestSummaryText(session.getLatestSummaryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .model(session.getModel())
                .thinking(session.getThinking())
                .thinkingEffort(session.getThinkingEffort())
                .deepThink(session.getDeepThink())
                .build();
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
