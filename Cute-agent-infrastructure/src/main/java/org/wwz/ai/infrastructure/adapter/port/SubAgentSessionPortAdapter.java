package org.wwz.ai.infrastructure.adapter.port;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionIdentity;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;

import java.time.LocalDateTime;

/**
 * 子智能体隐藏会话身份端口适配：复用既有会话账本 upsert 写入 {@code session_kind=subagent} 的会话行。
 * <p>
 * 幂等语义：已存在即 no-op。原因见计划书 §5.1 —— 身份列与运行计数都不应被重复派发/续跑覆盖，
 * 否则续跑会把子会话的 run_count 重置为 0，且可能让子会话漏回侧栏。
 */
@Component
@RequiredArgsConstructor
public class SubAgentSessionPortAdapter implements SubAgentSessionPort {

    private static final String DEFAULT_TITLE = "子智能体任务";

    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;

    @Override
    public void ensureChildSession(SubAgentSessionIdentity identity) {
        String childSessionId = identity.getChildSessionId();
        String parentSessionId = identity.getParentSessionId();
        LocalDateTime now = LocalDateTime.now();
        // 单条 INSERT ... ON DUPLICATE KEY UPDATE 完成「不存在才建行」；不能先查再插，
        // 否则并发派发都可能在查询时看到空行，后一次全量 upsert 会重置 run_count。
        executionLedgerWriteRepository.insertSessionIfAbsent(DialogueSessionUpsertRecord.builder()
                .sessionId(childSessionId)
                .userId(identity.getUserId())
                .sessionKind(SessionKind.SUBAGENT.code())
                .parentSessionId(parentSessionId)
                .rootSessionId(resolveRootSessionId(parentSessionId))
                .subAgentType(StringUtils.trimToNull(identity.getSubAgentType()))
                .title(StringUtils.defaultIfBlank(identity.getTitle(), DEFAULT_TITLE))
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(now)
                .lastActiveAt(now)
                .build());
    }

    /**
     * 解析树根：父会话自己的 {@code rootSessionId} 非空就沿用它，否则父就是根。
     * <p>{@code parent_session_id} 是权威血缘链，本列只是缓存；父行尚未回填时退化为
     * 「父即根」，两层结构下与最终值一致。</p>
     */
    private String resolveRootSessionId(String parentSessionId) {
        DialogueSession parent = executionLedgerWriteRepository.querySessionBySessionId(parentSessionId);
        if (parent == null || StringUtils.isBlank(parent.getRootSessionId())) {
            return parentSessionId;
        }
        return parent.getRootSessionId();
    }
}
