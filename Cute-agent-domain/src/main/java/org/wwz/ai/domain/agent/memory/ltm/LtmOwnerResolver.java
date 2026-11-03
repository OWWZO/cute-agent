package org.wwz.ai.domain.agent.memory.ltm;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.types.agent.user.UserRequestContext;

/**
 * 解析策展记忆 owner：优先 userId，其次 ERP 标识，最后按会话隔离兜底。
 * <p>无法解析出任何身份时返回 {@code null}，调用方必须跳过 LTM 读写（fail-closed），
 * 不再回退到全局 {@code anonymous} —— 那会让所有未认证调用共享同一份记忆。</p>
 */
@Slf4j
public final class LtmOwnerResolver {

    /** 无身份来源时的兜底 owner id；需要真实归属的调用方必须把它当作「未解析」处理。 */
    public static final String ANONYMOUS_OWNER_ID = "anonymous";

    private LtmOwnerResolver() {
    }

    public static LtmOwner resolve(String userId, String erp) {
        return resolve(userId, erp, null);
    }

    public static LtmOwner resolve(String userId, String erp, String sessionId) {
        String resolvedUserId = StringUtils.defaultIfBlank(userId, UserRequestContext.currentUserId());
        if (StringUtils.isNotBlank(resolvedUserId)) {
            return LtmOwner.user(resolvedUserId);
        }
        // 异步/调度线程没有请求上下文时会走到这里，靠下面的 erp / session 兜底，绝不再回退 anonymous。
        if (StringUtils.isNotBlank(erp)) {
            // ERP 与 userId 是不同身份来源，必须使用不同 owner type。
            return LtmOwner.erp(erp);
        }
        if (StringUtils.isNotBlank(sessionId)) {
            // 异步/调度线程没有请求上下文时，至少把兜底范围收敛到单个会话。
            log.warn("LTM owner 回退到会话级隔离：userId 与 erp 均为空，sessionId={}", sessionId);
            return LtmOwner.session(sessionId);
        }
        log.warn("LTM owner 无法解析：userId、erp、sessionId 均为空，跳过本次 LTM 读写");
        return null;
    }
}
