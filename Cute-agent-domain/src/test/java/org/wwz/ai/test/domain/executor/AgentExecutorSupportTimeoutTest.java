package org.wwz.ai.test.domain.executor;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 超时取消顺序回归：必须先把中断交给 worker，再发布 completion（依赖阶段同步执行）。
 */
public class AgentExecutorSupportTimeoutTest {

    @Test
    public void timeoutInterruptsWorkerBeforePublishingCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = AgentExecutorSupport.supplyAsync(executor, "test", () -> {
                started.countDown();
                try {
                    release.await();
                    return "done";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            });
            Assert.assertTrue(started.await(5, TimeUnit.SECONDS));

            Field delegateField = future.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            Future<?> delegate = (Future<?>) delegateField.get(future);

            AtomicBoolean delegateCancelledAtCallback = new AtomicBoolean(false);
            future.whenComplete((value, error) ->
                    delegateCancelledAtCallback.set(delegate.isCancelled()));

            AgentExecutorSupport.withTimeout(future, 100, TimeUnit.MILLISECONDS);
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception expected) {
                // 超时会以 TimeoutException / CancellationException 收口
            }

            Assert.assertTrue("超时回调执行前 worker 必须已被取消",
                    delegateCancelledAtCallback.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
