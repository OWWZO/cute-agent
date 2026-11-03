package org.wwz.ai.test.domain.util;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.util.ThreadUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadUtilTest {

    @Test
    public void usesBoundedPoolAndQueue() throws Exception {
        ThreadUtil.initPool(2);
        ThreadPoolExecutor executor = currentExecutor();

        Assert.assertEquals(2, executor.getMaximumPoolSize());
        Assert.assertEquals(1000, executor.getQueue().remainingCapacity());
        Assert.assertFalse("饱和时不能在提交线程偷偷执行",
                executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
    }

    @Test
    public void repeatedInitAppliesNewPoolSize() throws Exception {
        ThreadUtil.initPool(2);
        ThreadUtil.initPool(5);
        ThreadPoolExecutor executor = currentExecutor();

        Assert.assertEquals(5, executor.getCorePoolSize());
        Assert.assertEquals(5, executor.getMaximumPoolSize());
        ThreadUtil.initPool(2);
    }

    @Test
    public void executorFieldIsVolatile() throws Exception {
        Field field = ThreadUtil.class.getDeclaredField("executor");
        Assert.assertTrue("executor 必须 volatile 才能安全发布", Modifier.isVolatile(field.getModifiers()));
    }

    @Test
    public void awaitRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            ThreadUtil.await(new CountDownLatch(1));
            Assert.assertTrue("await 不得吞掉中断标记", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void sleepRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            ThreadUtil.sleep(10L);
            Assert.assertTrue("sleep 不得吞掉中断标记", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void saturatedQueueRejectsInsteadOfRunningOnCaller() throws Exception {
        ThreadUtil.initPool(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadUtil.execute(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Assert.assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                ThreadUtil.execute(() -> {
                });
            }
            boolean rejected = false;
            try {
                ThreadUtil.execute(() -> {
                });
            } catch (RejectedExecutionException expected) {
                rejected = true;
            }
            Assert.assertTrue("队列饱和必须显式拒绝", rejected);
        } finally {
            release.countDown();
            Thread.sleep(200L);
            ThreadUtil.initPool(2);
        }
    }

    private static ThreadPoolExecutor currentExecutor() throws Exception {
        Field field = ThreadUtil.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }
}
