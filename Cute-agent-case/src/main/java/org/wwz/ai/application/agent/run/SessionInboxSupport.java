package org.wwz.ai.application.agent.run;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;

/**
 * Session Inbox 的共享收口动作。
 */
@Slf4j
public final class SessionInboxSupport {

    private SessionInboxSupport() {
    }

    /**
     * 用户显式继续或发送新消息时释放 HELD；Stop 路径不应调用。
     */
    public static void releaseHeldUserMessages(SessionInboxPort sessionInboxPort, String sessionId) {
        if (sessionInboxPort == null || StringUtils.isBlank(sessionId)) {
            return;
        }
        try {
            sessionInboxPort.releaseHeldUserMessages(sessionId, null);
        } catch (Exception e) {
            log.warn("release held session inbox failed sessionId={}", sessionId, e);
        }
    }
}
