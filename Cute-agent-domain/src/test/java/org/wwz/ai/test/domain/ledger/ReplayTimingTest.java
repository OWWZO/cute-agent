package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并发收口/读取 ReplayTiming 时，finishedAt 必须作为最后一个发布字段：
 * 读端一旦看到「已结束」，就一定能看到 duration/source。
 */
public class ReplayTimingTest {

    @Test
    public void finishRuntimePublishesDurationAndSource() {
        ReplayTiming timing = ReplayTiming.startRuntime();

        ReplayTiming finished = ReplayTiming.finishRuntime(timing);

        Assert.assertSame(timing, finished);
        Assert.assertNotNull(finished.getDurationMs());
        Assert.assertEquals(ReplayTiming.SOURCE_RUNTIME, finished.getSource());
        Assert.assertNotNull(finished.getFinishedAt());
    }

    @Test
    public void concurrentCopyNeverSeesFinishedWithoutDuration() throws Exception {
        for (int i = 0; i < 200; i++) {
            ReplayTiming timing = ReplayTiming.startRuntime();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean halfWritten = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 200; n++) {
                    ReplayTiming snapshot = ReplayTiming.copyOf(timing);
                    if (snapshot != null && snapshot.getFinishedAt() != null && snapshot.getDurationMs() == null) {
                        halfWritten.set(true);
                    }
                }
            });
            reader.start();
            start.countDown();
            ReplayTiming.finishRuntime(timing);
            reader.join();

            Assert.assertFalse("观察者看到了已结束但没有耗时的半成品", halfWritten.get());
        }
    }
}
