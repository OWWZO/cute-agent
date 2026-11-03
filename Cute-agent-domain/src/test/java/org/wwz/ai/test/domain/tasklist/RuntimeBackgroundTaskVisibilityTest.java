package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 可见性回归：任务对象发布进注册表后仍被后台线程改写，读者不经锁读取，字段必须 volatile。
 */
public class RuntimeBackgroundTaskVisibilityTest {

    @Test
    public void crossThreadFieldsAreVolatile() throws Exception {
        for (String name : new String[]{"status", "endedAtMs", "agentId", "output",
                "errorMsg", "totalToolUseCount", "totalDurationMs", "future"}) {
            Field field = RuntimeBackgroundTask.class.getDeclaredField(name);
            Assert.assertTrue(name + " 必须是 volatile",
                    Modifier.isVolatile(field.getModifiers()));
        }
    }
}
