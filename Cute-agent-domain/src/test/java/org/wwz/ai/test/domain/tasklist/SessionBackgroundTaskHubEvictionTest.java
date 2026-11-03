package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 静态 session map 必须有上限，否则长跑服务会持续泄漏空闲 registry；
 * 淘汰只能发生在没有 running 任务的会话上。
 */
public class SessionBackgroundTaskHubEvictionTest {

    @Test
    public void idleRegistriesAreEvictedWhenHubExceedsLimit() throws Exception {
        try {
            for (int i = 0; i < 1100; i++) {
                SessionBackgroundTaskHub.getOrCreate("session-" + i, null);
            }

            Assert.assertTrue("空闲 registry 数量必须有上限",
                    registryCount() <= 1024);
        } finally {
            SessionBackgroundTaskHub.clearAll();
        }
    }

    @Test
    public void runningRegistryIsNeverEvicted() {
        try {
            RuntimeBackgroundTaskRegistry running = SessionBackgroundTaskHub.getOrCreate("keep-running", null);
            running.register("local_agent", "running", null);

            for (int i = 0; i < 1100; i++) {
                SessionBackgroundTaskHub.getOrCreate("idle-" + i, null);
            }

            Assert.assertTrue("有 running 任务的会话不得被淘汰",
                    SessionBackgroundTaskHub.hasRunning("keep-running"));
        } finally {
            SessionBackgroundTaskHub.clearAll();
        }
    }

    @SuppressWarnings("unchecked")
    private static int registryCount() throws Exception {
        Field field = SessionBackgroundTaskHub.class.getDeclaredField("BY_SESSION");
        field.setAccessible(true);
        return ((Map<String, ?>) field.get(null)).size();
    }
}
