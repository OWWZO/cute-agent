package org.wwz.ai.domain.agent.ledger.replay.projector.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.function.Function;

/**
 * 历史回放时按 tool invocation 反查 HITL 让步的持久化状态。
 * <p>
 * AskUserQuestion 与 RequestDesktopControl 两个 projector 的反查逻辑逐字相同：
 * 先按业务 id 直查，未命中再按 session 扫开放记录并用 toolInvocationId /
 * toolCallId 兜底匹配。共用的安全语义是"历史回放不能因附属状态读失败而中断"，
 * 因此仓储异常一律吞掉返回 null。
 */
@Slf4j
public final class PersistedYieldLookup {

    private PersistedYieldLookup() {
    }

    /**
     * @param businessId   业务 id（questionId / controlId），可空
     * @param invocation   当前 tool invocation；为空或没有 sessionId 时不做兜底扫描
     * @param byId         按业务 id 直查
     * @param openBySession 按 sessionId 列出开放记录
     * @param toolInvocationIdOf 取记录的 toolInvocationId
     * @param toolCallIdOf      取记录的 toolCallId
     */
    public static <T> T find(String businessId,
                             ToolInvocationView invocation,
                             Function<String, T> byId,
                             Function<String, List<T>> openBySession,
                             Function<T, Long> toolInvocationIdOf,
                             Function<T, String> toolCallIdOf) {
        return find(businessId, invocation, byId, openBySession, toolInvocationIdOf, toolCallIdOf, null);
    }

    /**
     * 带回放级缓存的版本：同一 session 的开放记录在一次 replay 内只查一次。
     * <p>回放循环会为每个 tool invocation 调一次本方法；未命中 {@code byId} 时若每次都
     * 扫 session 开放记录，历史越长查询越多。缓存挂在 {@link AgentStreamAccumulator} 上，
     * 生命周期与单次 projectHistory 对齐，不会跨请求串数据。</p>
     */
    public static <T> T find(String businessId,
                             ToolInvocationView invocation,
                             Function<String, T> byId,
                             Function<String, List<T>> openBySession,
                             Function<T, Long> toolInvocationIdOf,
                             Function<T, String> toolCallIdOf,
                             AgentStreamAccumulator state) {
        if (StringUtils.isNotBlank(businessId)) {
            try {
                T byBusinessId = byId.apply(businessId);
                if (byBusinessId != null) {
                    return byBusinessId;
                }
            } catch (RuntimeException e) {
                // 历史回放不能因问询/桌面控制附属状态读取失败而中断。
                log.warn("yield lookup by business id failed, businessId={}", businessId, e);
            }
        }
        if (invocation == null || StringUtils.isBlank(invocation.getSessionId())) {
            return null;
        }
        try {
            for (T record : loadOpenBySession(openBySession, invocation.getSessionId(), state)) {
                if (record == null) {
                    continue;
                }
                if (invocation.getId() != null
                        && invocation.getId().equals(toolInvocationIdOf.apply(record))) {
                    return record;
                }
                if (StringUtils.isNotBlank(invocation.getToolCallId())
                        && invocation.getToolCallId().equals(toolCallIdOf.apply(record))) {
                    return record;
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("yield lookup by session failed, sessionId={}, toolCallId={}",
                    invocation.getSessionId(), invocation.getToolCallId(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> loadOpenBySession(Function<String, List<T>> openBySession,
                                                  String sessionId,
                                                  AgentStreamAccumulator state) {
        if (state == null) {
            return openBySession.apply(sessionId);
        }
        // 用 loader 的 lambda 类区分记录类型（问询 vs 桌面控制），再叠加 sessionId 作为缓存键。
        String cacheKey = "persistedYield:" + openBySession.getClass().getName() + ":" + sessionId;
        Object cached = state.getReplayLookup(cacheKey);
        if (cached instanceof List<?> cachedRecords) {
            return (List<T>) cachedRecords;
        }
        List<T> loaded = openBySession.apply(sessionId);
        if (loaded == null) {
            loaded = List.of();
        }
        state.putReplayLookup(cacheKey, loaded);
        return loaded;
    }
}
