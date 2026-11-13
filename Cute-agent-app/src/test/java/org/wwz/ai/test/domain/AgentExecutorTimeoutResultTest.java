package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AgentExecutorTimeoutResultTest {

    @Test
    public void timeoutKeepsTimeoutExceptionInsteadOfCancellation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = AgentExecutorSupport.withTimeout(
                    AgentExecutorSupport.supplyAsync(executor, "timeout-result", () -> {
                        started.countDown();
                        await(release);
                        return "late";
                    }),
                    50,
                    TimeUnit.MILLISECONDS);
            Assert.assertTrue(started.await(2, TimeUnit.SECONDS));

            try {
                future.get(2, TimeUnit.SECONDS);
                Assert.fail("blocked task must time out");
            } catch (ExecutionException expected) {
                Assert.assertTrue("timeout must not be rewritten as cancellation",
                        expected.getCause() instanceof TimeoutException);
            } catch (CancellationException unexpected) {
                Assert.fail("timeout must not become a cancellation");
            }
            Assert.assertFalse(future.isCancelled());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void explicitCancelStillReportsCancellationException() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = AgentExecutorSupport.supplyAsync(
                    executor, "cancel-result", () -> {
                        started.countDown();
                        await(release);
                        return "late";
                    });
            Assert.assertTrue(started.await(2, TimeUnit.SECONDS));
            Assert.assertTrue(future.cancel(true));

            try {
                future.get(2, TimeUnit.SECONDS);
                Assert.fail("explicit cancel must cancel the future");
            } catch (CancellationException expected) {
                Assert.assertTrue(future.isCancelled());
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
