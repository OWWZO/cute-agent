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

    /**
     * 类型解析结果。
     * <p>{@link Status#NOT_APPLICABLE} 才表示「保留全量工具池」（主会话/历史会话无类型记录）；
     * {@link Status#UNRESOLVABLE} 表示会话明确记录了类型但注册表已找不到，调用方必须 fail-closed，
     * 不能因为后台停用/删除定义反而给老会话扩权。</p>
     */
    public enum Status {
        NOT_APPLICABLE,
        RESOLVED,
        UNRESOLVABLE
    }

    public record Resolution(Status status, SubAgentDefinition definition) {

        public static Resolution notApplicable() {
            return new Resolution(Status.NOT_APPLICABLE, null);
        }

        public static Resolution resolved(SubAgentDefinition definition) {
            return new Resolution(Status.RESOLVED, definition);
        }

        public static Resolution unresolvable() {
            return new Resolution(Status.UNRESOLVABLE, null);
        }

        public boolean isUnresolvable() {
            return status == Status.UNRESOLVABLE;
        }

        public boolean isResolved() {
            return status == Status.RESOLVED && definition != null;
        }
    }
    /** 会话身份在工具装配期间很少变化，短缓存避免 retry / 子轮次重复读 session 行。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static final int CACHE_MAX_ENTRIES = 1024;

    private final SubAgentRegistry subAgentRegistry;
    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final Map<String, CachedDefinition> cache = new ConcurrentHashMap<>();

    private record CachedDefinition(Resolution resolution, long expiresAtMs) {
    }

    public Resolution resolve(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return Resolution.notApplicable();
        }
        long now = System.currentTimeMillis();
        CachedDefinition cached = cache.get(sessionId);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.resolution();
        }
        Resolution resolution = resolveUncached(sessionId);
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            // 简单上限：过期项先清，仍满则整体清空，保证缓存不会无界增长。
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(sessionId, new CachedDefinition(resolution, now + CACHE_TTL_MS));
        return resolution;
    }

    private Resolution resolveUncached(String sessionId) {
        DialogueSession session = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (session == null || !SessionKind.SUBAGENT.code().equals(session.getSessionKind())) {
            return Resolution.notApplicable();
        }
        String agentType = StringUtils.trimToNull(session.getSubAgentType());
        if (agentType == null) {
            // 2026-10 之前落库的子会话没有这一列，无从得知类型；回退全量工具池，
            // 与它们历史行为一致，不因缺少类型而丢掉能力。
            log.debug("subagent session without type, keep full tool pool sessionId={}", sessionId);
            return Resolution.notApplicable();
        }
        SubAgentDefinition definition = subAgentRegistry.find(agentType).orElse(null);
        if (definition == null) {
            // 定义被删除/停用：明确记录过类型就不能再保留全量工具池，
            // 否则一次后台配置动作会让老会话拿到比定义允许更多的工具。
            log.warn("subagent type not found in registry, deny tools fail-closed sessionId={} type={}",
                    sessionId, agentType);
            return Resolution.unresolvable();
        }
        return Resolution.resolved(definition);
    }
}
