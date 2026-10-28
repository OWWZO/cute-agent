package org.wwz.ai.application.agent.query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;

/**
 * 会话级偏好（模型 / 思考 / Deep Think）的 upsert。
 *
 * <p>语义是「用户在本轮显式选择的值」：未传（{@code null}）时不覆盖库里已有的值，
 * 由 SQL 的 {@code COALESCE(VALUES(col), col)} 保证。</p>
 *
 * <p>为什么不复用 {@code SubAgentSessionPortAdapter.ensureChildSession}：那个是「已存在即 no-op」，
 * 与本服务「偏好需要更新」的语义相反。两者都走 {@code upsertSession}，
 * 但前者只在建行时生效，后者只更新偏好列。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionPreferencesApplicationService {

    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;

    /**
     * 落库本轮用户显式选择的会话偏好。
     * <p>四个都没传时直接返回，不产生任何 DB 调用。</p>
     *
     * <p><b>只更新四列</b>：走 {@code updateSessionPreferences} 而不是 {@code upsertSession}——
     * 后者整行覆盖 title/status/run_count，本服务不持有这些值，会把会话头清空。</p>
     */
    public void upsertPreferences(String sessionId,
                                  String model,
                                  Boolean thinking,
                                  String thinkingEffort,
                                  Integer deepThink) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        if (model == null && thinking == null && thinkingEffort == null && deepThink == null) {
            return;
        }
        executionLedgerWriteRepository.updateSessionPreferences(
                sessionId, model, thinking, thinkingEffort, deepThink);
    }
}
