package org.wwz.ai.domain.agent.runtime.subagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按<b>会话身份</b>解析子 Agent 定义，供「用户直聊子会话」（模式 2）裁剪工具池。
 * <p>
 * 模式 1（父派发）在派发时手上就有定义；模式 2 没有父上下文，只能从会话行读
 * {@code sub_agent_type} 再查注册表。两模式因此共用同一份定义，
 * 工具池由「这个会话是什么 Agent」决定，而不是由「谁派发的」决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentSessionToolScope {

    /** 会话身份在工具装配期间很少变化，短缓存避免 retry / 子轮次重复读 session 行。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static final int CACHE_MAX_ENTRIES = 1024;

    private final SubAgentRegistry subAgentRegistry;
    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final Map<String, CachedDefinition> cache = new ConcurrentHashMap<>();

    private record CachedDefinition(SubAgentDefinition definition, long expiresAtMs) {
    }

    /**
     * 解析该会话应使用的子 Agent 定义。
     *
     * @return 子会话且类型可解析时返回定义；主会话、历史会话未记录类型、
     *         或类型已下架时返回 {@code null}，调用方据此保留全量工具池
     */
    public SubAgentDefinition resolve(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedDefinition cached = cache.get(sessionId);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.definition();
        }
        SubAgentDefinition definition = resolveUncached(sessionId);
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            // 简单上限：过期项先清，仍满则整体清空，保证缓存不会无界增长。
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(sessionId, new CachedDefinition(definition, now + CACHE_TTL_MS));
        return definition;
    }

    private SubAgentDefinition resolveUncached(String sessionId) {
        DialogueSession session = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (session == null || !SessionKind.SUBAGENT.code().equals(session.getSessionKind())) {
            return null;
        }
        String agentType = StringUtils.trimToNull(session.getSubAgentType());
        if (agentType == null) {
            // 2026-10 之前落库的子会话没有这一列，无从得知类型；回退全量工具池，
            // 与它们历史行为一致，不因缺少类型而丢掉能力。
            log.debug("subagent session without type, keep full tool pool sessionId={}", sessionId);
            return null;
        }
        SubAgentDefinition definition = subAgentRegistry.find(agentType).orElse(null);
        if (definition == null) {
            // 定义被删除/停用：继续用全量工具池，避免一个后台配置动作让老会话无法续聊。
            log.warn("subagent type not found in registry, keep full tool pool sessionId={} type={}",
                    sessionId, agentType);
        }
        return definition;
    }
}
