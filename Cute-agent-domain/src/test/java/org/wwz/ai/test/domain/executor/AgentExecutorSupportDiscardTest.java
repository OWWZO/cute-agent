package org.wwz.ai.test.domain.executor;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 丢弃策略会吞掉任务且不回调 Future，提交侧必须显式失败而不是永久挂起。
 */
public class AgentExecutorSupportDiscardTest {

    @Test
    public void discardPolicyFailsFutureInsteadOfHanging() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        try {
            CompletableFuture<String> future = AgentExecutorSupport.supplyAsync(pool, "test", () -> "ok");

            Assert.assertTrue(future.isCompletedExceptionally());
            try {
                future.join();
                Assert.fail("应抛出 busy 异常");
            } catch (CompletionException expected) {
                Assert.assertTrue(expected.getCause() instanceof AgentExecutorBusyException);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
