package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BackgroundSubAgentExecutorVirtualThreadTest {

    @Test
    public void backgroundExecutorRunsOnVirtualThreads() throws Exception {
        Field field = BackgroundSubAgentExecutor.class.getDeclaredField("BACKGROUND_EXECUTOR");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(null);

        Future<Boolean> virtual = executor.submit(() -> Thread.currentThread().isVirtual());

        Assert.assertTrue(virtual.get(5, TimeUnit.SECONDS));
    }
}
