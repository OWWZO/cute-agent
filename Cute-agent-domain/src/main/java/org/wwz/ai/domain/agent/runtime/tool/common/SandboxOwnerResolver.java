package org.wwz.ai.domain.agent.runtime.tool.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerType;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

/**
 * 沙箱归属解析：只接受稳定的用户级身份，不复用 LTM 的会话级隔离兜底。
 * <p>
 * 子会话可以脱离父 Agent 独立续聊，因此优先按 sessionId 从账本恢复 {@code userId}；
 * 账本不可用时才回退当前上下文。无法确认真实用户时返回 {@code null}，由调用方 fail-closed。
 */
@Slf4j
public final class SandboxOwnerResolver {

    private SandboxOwnerResolver() {
    }

    public static String resolve(AgentContext context) {
        if (context == null) {
            return null;
        }
        String persistedUserId = resolvePersistedUserId(context);
        if (StringUtils.isNotBlank(persistedUserId)) {
            return LtmOwner.user(persistedUserId).asOwnerKey();
        }
        LtmOwner owner = context.getLtmOwner();
        if (owner != null && owner.getType() != LtmOwnerType.SESSION) {
            return owner.asOwnerKey();
        }
        String contextUserId = StringUtils.trimToNull(context.getUserId());
        return contextUserId == null ? null : LtmOwner.user(contextUserId).asOwnerKey();
    }

    private static String resolvePersistedUserId(AgentContext context) {
        CuteRuntimeDependencies dependencies = context.getRuntimeDependencies();
        SubAgentSessionPort port = dependencies == null
                ? null
                : dependencies.getOptionalSubAgentSessionPort();
        String sessionId = StringUtils.trimToNull(context.getSessionId());
        if (port == null || sessionId == null) {
            return null;
        }
        try {
            return StringUtils.trimToNull(port.findSessionUserId(sessionId));
        } catch (RuntimeException e) {
            log.warn("{} sandbox owner ledger lookup failed sessionId={}: {}",
                    context.getRequestId(), sessionId, e.getMessage());
            return null;
        }
    }
}
