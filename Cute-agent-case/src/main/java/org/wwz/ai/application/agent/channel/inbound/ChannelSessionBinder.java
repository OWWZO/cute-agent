package org.wwz.ai.application.agent.channel.inbound;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 给 IM 渠道会话打上 {@code binding_id} 与出站目标（chatType / targetId），
 * 供 owner 查看权限和唤醒轮/主动推送恢复目标使用。
 * <p>为什么在 channel 侧直接写库而不是改 command 链路：{@code AgentExecutionCommand} 是 domain
 * 的运行时命令，加一个纯渠道维度的字段会污染核心链路，而这里只需一次幂等 upsert。</p>
 *
 * <p>写入时机必须在 {@code submitAgentQuery} <b>之后</b>：会话行由 {@code ensureSessionAccessible}
 * 创建，提前写会用不完整的字段覆盖整行。</p>
 */
@Slf4j
public class ChannelSessionBinder {

    private final IExecutionLedgerReadRepository ledgerReadRepository;
    private final IExecutionLedgerWriteRepository ledgerWriteRepository;

    public ChannelSessionBinder(IExecutionLedgerReadRepository ledgerReadRepository,
                                IExecutionLedgerWriteRepository ledgerWriteRepository) {
        this.ledgerReadRepository = ledgerReadRepository;
        this.ledgerWriteRepository = ledgerWriteRepository;
    }

    /**
     * 幂等：已带 bindingId、出站目标完整且最近入站消息相同的会话不再写。任何异常都吞掉——打标记失败不该让用户收不到回复。
     *
     * @param chatType 出站会话形态（C2C/GROUP/...），与 targetId 一起供后续主动推送恢复目标
     * @param targetId 出站目标 ID
     * @param messageId 最近入站消息 ID，供终答走被动回复
     * @param receivedAt 最近入站时刻，供适配器判断被动回复窗口
     */
    public void bind(String sessionId, Long bindingId, String userId, String chatType, String targetId,
                     String messageId, Instant receivedAt) {
        if (sessionId == null || sessionId.isBlank() || bindingId == null) {
            return;
        }
        try {
            DialogueSession existing = ledgerReadRepository.querySessionEntity(sessionId);
            if (existing == null) {
                return;
            }
            boolean sameBinding = bindingId.equals(existing.getBindingId());
            boolean targetComplete = StringUtils.isNotBlank(existing.getChannelChatType())
                    && StringUtils.isNotBlank(existing.getChannelTargetId());
            boolean sameReply = StringUtils.equals(messageId, existing.getChannelLastMsgId())
                    && (receivedAt == null ? existing.getChannelLastReceivedAt() == null
                    : receivedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
                    .equals(existing.getChannelLastReceivedAt()));
            if (sameBinding && sameReply
                    && (targetComplete || StringUtils.isBlank(chatType) || StringUtils.isBlank(targetId))) {
                return;
            }
            // 用现有字段整体回写，避免把 upsert 当成「部分更新」用。
            ledgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(existing.getSessionId())
                    .userId(existing.getUserId())
                    .bindingId(bindingId)
                    .channelChatType(StringUtils.defaultIfBlank(chatType, existing.getChannelChatType()))
                    .channelTargetId(StringUtils.defaultIfBlank(targetId, existing.getChannelTargetId()))
                    .channelLastMsgId(messageId)
                    .channelLastReceivedAt(receivedAt == null
                            ? null
                            : receivedAt.atOffset(ZoneOffset.UTC).toLocalDateTime())
                    .sessionKind(existing.getSessionKind())
                    .parentSessionId(existing.getParentSessionId())
                    .rootSessionId(existing.getRootSessionId())
                    .title(existing.getTitle())
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
        } catch (Exception e) {
            log.warn("channel session bind failed sessionId={} bindingId={}", sessionId, bindingId, e);
        }
    }
}
