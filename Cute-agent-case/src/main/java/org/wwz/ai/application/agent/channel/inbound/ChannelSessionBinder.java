package org.wwz.ai.application.agent.channel.inbound;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;

/**
 * 给 IM 渠道会话打上 {@code binding_id}，供 owner 查看权限使用。
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
     * 幂等：已带 bindingId 的会话不再写。任何异常都吞掉——打标记失败不该让用户收不到回复。
     */
    public void bind(String sessionId, Long bindingId, String userId) {
        if (sessionId == null || sessionId.isBlank() || bindingId == null) {
            return;
        }
        try {
            DialogueSession existing = ledgerReadRepository.querySessionEntity(sessionId);
            if (existing == null || bindingId.equals(existing.getBindingId())) {
                return;
            }
            // 用现有字段整体回写，避免把 upsert 当成「部分更新」用。
            ledgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(existing.getSessionId())
                    .userId(existing.getUserId())
                    .bindingId(bindingId)
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
