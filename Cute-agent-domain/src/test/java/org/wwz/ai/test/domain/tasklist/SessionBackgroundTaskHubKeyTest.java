package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;

/**
 * 创建与查询必须使用同一个 session key。
 */
public class SessionBackgroundTaskHubKeyTest {

    @Test
    public void blankSessionIdUsesRequestIdForBothCreateAndLookup() {
        try {
            RuntimeBackgroundTaskRegistry registry = SessionBackgroundTaskHub.getOrCreate("req-1", null);
            registry.register("local_agent", "running", null);

            Assert.assertTrue(SessionBackgroundTaskHub.hasRunning(" ", "req-1"));
            Assert.assertEquals("req-1", SessionBackgroundTaskHub.keyFor(null, "req-1"));
            Assert.assertTrue(SessionBackgroundTaskHub.hasRunning("req-1"));
        } finally {
            SessionBackgroundTaskHub.clearAll();
        }
    }

    @Test
    public void contextCreateAndHubLookupUseRequestIdForBlankSession() {
        try {
            org.wwz.ai.domain.agent.runtime.agent.AgentContext context =
                    org.wwz.ai.domain.agent.runtime.agent.AgentContext.builder()
                            .requestId("req-context")
                            .sessionId(" ")
                            .build();
            context.requireBackgroundTasks().register("local_agent", "running", null);

            Assert.assertTrue(SessionBackgroundTaskHub.hasRunning(null, "req-context"));
            Assert.assertEquals("req-context", SessionBackgroundTaskHub.keyFor(" ", "req-context"));
        } finally {
            SessionBackgroundTaskHub.clearAll();
        }
    }
}
