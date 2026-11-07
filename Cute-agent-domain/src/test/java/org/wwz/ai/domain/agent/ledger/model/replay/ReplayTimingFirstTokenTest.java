package org.wwz.ai.domain.agent.ledger.model.replay;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.handler.BaseAgentStreamEventHandler;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    @Test
    public void ledgerReplayTimingCarriesFirstTokenAt() throws Exception {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(2);
        LocalDateTime firstTokenAt = startedAt.plusNanos(200_000_000L);
        LocalDateTime finishedAt = startedAt.plusSeconds(1);
        ReplayProjector projector = new ReplayProjector(new ToolInvocationProjectorRegistry(
                List.of(), new DefaultToolInvocationProjector()));
        Method resolve = ReplayProjector.class.getDeclaredMethod(
                "resolveReplayTiming",
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class, Long.class);
        resolve.setAccessible(true);

        ReplayTiming timing = (ReplayTiming) resolve.invoke(
                projector, startedAt, firstTokenAt, finishedAt, 1000L);

        Assert.assertEquals(firstTokenAt, timing.getFirstTokenAt());
    }

    @Test
    public void mapRehydrationCarriesFirstTokenAt() throws Exception {
        LocalDateTime firstTokenAt = LocalDateTime.now().minusNanos(200_000_000L);
        BaseAgentStreamEventHandler handler = new TestHandler();
        Method convert = BaseAgentStreamEventHandler.class.getDeclaredMethod(
                "toReplayTiming", Object.class);
        convert.setAccessible(true);

        ReplayTiming timing = (ReplayTiming) convert.invoke(handler,
                Map.of("firstTokenAt", firstTokenAt.toString()));

        Assert.assertEquals(firstTokenAt, timing.getFirstTokenAt());
    }

    private static final class TestHandler extends BaseAgentStreamEventHandler {
        private TestHandler() {
            super(new ReplayProjector(new ToolInvocationProjectorRegistry(
                    List.of(), new DefaultToolInvocationProjector())));
        }
    }
}
