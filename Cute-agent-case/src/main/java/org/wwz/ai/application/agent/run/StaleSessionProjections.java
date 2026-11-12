package org.wwz.ai.application.agent.run;

import org.slf4j.Logger;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;

import java.util.List;

/**
 * 关闭已快照的会话旧投影，不重新读取注册表。
 */
final class StaleSessionProjections {

    private StaleSessionProjections() {
    }

    static void complete(String sessionId,
                         List<AgentStreamProjection> projections,
                         AgentStreamProjection keep,
                         Logger log) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        for (AgentStreamProjection projection : projections) {
            if (projection == keep) {
                continue;
            }
            try {
                projection.complete();
            } catch (Exception e) {
                log.debug("complete stale projection failed sessionId={}", sessionId, e);
            }
        }
    }
}
