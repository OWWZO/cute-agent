package org.wwz.ai.domain.agent.memory.ltm;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.types.agent.user.UserRequestContext;

/**
 * 解析策展记忆 owner：优先 userId，其次 ERP 标识，再回退到请求上下文。
 */
@Slf4j
public final class LtmOwnerResolver {

    private LtmOwnerResolver() {
    }

    public static LtmOwner resolve(String userId, String erp) {
        String resolvedUserId = StringUtils.defaultIfBlank(userId, UserRequestContext.currentUserId());
        resolvedUserId = StringUtils.defaultIfBlank(resolvedUserId, erp);
        if (StringUtils.isBlank(resolvedUserId)) {
            // 异步/调度线程没有请求上下文时会走到这里，留下线索便于排查记忆错配。
            log.warn("LTM owner 解析回退到 anonymous：userId 与 erp 均为空，且当前线程无用户请求上下文");
            resolvedUserId = "anonymous";
        }
        return LtmOwner.user(resolvedUserId);
    }
}
