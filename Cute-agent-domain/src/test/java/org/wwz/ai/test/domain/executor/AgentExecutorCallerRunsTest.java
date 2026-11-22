package org.wwz.ai.test.domain.executor;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

public class AgentExecutorCallerRunsTest {

    @Test
    public void rejectedSubmissionStaysObservableForCallerRunsFallback() {
        CompletableFuture<String> future = AgentExecutorSupport.supplyAsync(
                command -> {
                    throw new RejectedExecutionException("full");
                },
                "agent",
                () -> "pool-result");

        Assert.assertTrue(future.isCompletedExceptionally());
        try {
            future.getNow(null);
        } catch (CompletionException e) {
            Assert.assertTrue(e.getCause() instanceof AgentExecutorBusyException);
            return;
        }
        Assert.fail("拒绝必须转换为 AgentExecutorBusyException");
    }
}
