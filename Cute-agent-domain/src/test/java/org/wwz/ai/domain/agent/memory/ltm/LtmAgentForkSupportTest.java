package org.wwz.ai.domain.agent.memory.ltm;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LTM fork 超时：必须同时中断线程并置协作取消，避免任务在超时结果返回后继续写共享记忆库。
 */
public class LtmAgentForkSupportTest {

    @Test
    public void timeoutCancelsFutureAndForkCancellationToken() throws Exception {
        @SuppressWarnings("unchecked")
        Future<LtmForkRunResult> future = mock(Future.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("timeout"));
        RunCancellation cancellation = new RunCancellation();

        LtmForkRunResult result = LtmAgentForkSupport.awaitFork(
                future, cancellation, "req-fork", "review", 3, System.currentTimeMillis(), 10L);

        Assert.assertEquals(LtmForkRunResult.STATUS_TIMEOUT, result.getStatus());
        Assert.assertTrue("超时后必须置协作取消，让不响应中断的模型/SSE 在下一步停止",
                cancellation.isCancelled());
        verify(future).cancel(true);
    }
}
