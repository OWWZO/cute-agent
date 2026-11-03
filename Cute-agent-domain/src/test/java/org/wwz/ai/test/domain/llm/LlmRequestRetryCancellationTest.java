package org.wwz.ai.test.domain.llm;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.llm.LlmRequestRetry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 组合后的 Future 取消必须传递给在途请求，且取消后不再执行映射。
 */
public class LlmRequestRetryCancellationTest {

    @Test
    public void mapperDoesNotRunAfterResultCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        AtomicBoolean mapped = new AtomicBoolean();
        CompletableFuture<Integer> result = LlmRequestRetry.mapCancellable(source, value -> {
            mapped.set(true);
            return value.length();
        });

        result.cancel(true);
        source.complete("payload");

        Assert.assertFalse("取消后不得再执行 mapper", mapped.get());
        Assert.assertTrue(result.isCancelled());
    }

    @Test
    public void propagateCancellationCancelsActiveRequest() {
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> activeRequest = new CompletableFuture<>();

        CompletableFuture<String> bridged = LlmRequestRetry.propagateCancellation(result, activeRequest);
        result.cancel(true);

        Assert.assertTrue(bridged.isCancelled());
        Assert.assertTrue("取消必须传播到在途请求", activeRequest.isCancelled());
    }
}
