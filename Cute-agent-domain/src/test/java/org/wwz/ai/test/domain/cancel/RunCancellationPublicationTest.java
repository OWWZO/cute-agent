package org.wwz.ai.test.domain.cancel;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消标记与原因必须作为整体发布：读者不能看到「已取消但原因为空」。
 */
public class RunCancellationPublicationTest {

    @Test
    public void readersNeverObserveCancelledFlagWithNullReason() throws Exception {
        for (int round = 0; round < 200; round++) {
            RunCancellation cancellation = new RunCancellation();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<String> violation = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (!cancellation.isCancelled()) {
                    Thread.onSpinWait();
                }
                if (cancellation.getReason() == null || cancellation.getCancelledAtMs() == 0) {
                    violation.set("cancelled with empty metadata");
                }
            });
            reader.start();
            start.countDown();
            cancellation.cancel("stop");
            reader.join(2000);

            Assert.assertNull(violation.get());
            Assert.assertEquals("stop", cancellation.getReason());
        }
    }

    @Test
    public void losingCancelDoesNotOverwriteWinnerMetadata() {
        RunCancellation cancellation = new RunCancellation();

        Assert.assertTrue(cancellation.cancel("winner"));
        Assert.assertFalse(cancellation.cancel("loser"));
        Assert.assertEquals("winner", cancellation.getReason());
    }
}
