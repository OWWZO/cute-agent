package org.wwz.ai.domain.agent.ledger.model.replay;

import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;

public class ReplayTimingFirstTokenTest {

    @Test
    public void markFirstTokenWritesOnlyOnceAndSurvivesCopy() throws Exception {
        ReplayTiming timing = ReplayTiming.builder()
                .startedAt(LocalDateTime.now().minusSeconds(1))
                .source(ReplayTiming.SOURCE_RUNTIME)
                .build();

        timing.markFirstToken();
        LocalDateTime first = timing.getFirstTokenAt();
        Thread.sleep(2L);
        timing.markFirstToken();

        Assert.assertNotNull(first);
        Assert.assertEquals(first, timing.getFirstTokenAt());
        Assert.assertEquals(first, ReplayTiming.copyOf(timing).getFirstTokenAt());
    }

    @Test
    public void markFirstTokenDoesNothingWithoutStartTime() {
        ReplayTiming timing = ReplayTiming.builder()
                .source(ReplayTiming.SOURCE_RUNTIME)
                .build();

        timing.markFirstToken();

        Assert.assertNull(timing.getFirstTokenAt());
    }
}
