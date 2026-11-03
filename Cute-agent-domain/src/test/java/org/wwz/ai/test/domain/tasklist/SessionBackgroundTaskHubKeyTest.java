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
    public void blankSessionIdFallsBackToDefaultForBothCreateAndLookup() {
        try {
            RuntimeBackgroundTaskRegistry registry = SessionBackgroundTaskHub.getOrCreate(null, null);
            registry.register("local_agent", "running", null);

            Assert.assertTrue(SessionBackgroundTaskHub.hasRunning(" ", "req-1"));
            Assert.assertEquals(SessionBackgroundTaskHub.keyFor(null),
                    SessionBackgroundTaskHub.keyFor(null, "req-1"));
        } finally {
            SessionBackgroundTaskHub.clearAll();
        }
    }
}
