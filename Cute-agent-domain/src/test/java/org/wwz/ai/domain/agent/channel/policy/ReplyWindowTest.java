package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplyWindowTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    public void openInsideWindow() {
        ReplyWindow window = ReplyWindow.ofSeconds(300);
        assertTrue(window.isOpen(RECEIVED, RECEIVED.plusSeconds(299)));
    }

    @Test
    public void closedExactlyAtTtl() {
        ReplyWindow window = ReplyWindow.ofSeconds(300);
        // 边界取「关闭」，避免恰好等于 TTL 时行为含糊。
        assertFalse(window.isOpen(RECEIVED, RECEIVED.plusSeconds(300)));
    }

    @Test
    public void closedAfterTtl() {
        ReplyWindow window = ReplyWindow.ofSeconds(300);
        assertFalse(window.isOpen(RECEIVED, RECEIVED.plusSeconds(301)));
    }

    @Test
    public void clockSkewDoesNotProduceNegativeAge() {
        ReplyWindow window = ReplyWindow.ofSeconds(300);
        assertEquals(Duration.ZERO, window.age(RECEIVED, RECEIVED.minusSeconds(5)));
        assertTrue(window.isOpen(RECEIVED, RECEIVED.minusSeconds(5)));
    }

    @Test
    public void nullTimestampsAreTreatedAsClosed() {
        ReplyWindow window = ReplyWindow.defaults();
        assertFalse(window.isOpen(null, RECEIVED));
        assertFalse(window.isOpen(RECEIVED, null));
        assertEquals(Duration.ZERO, window.age(null, RECEIVED));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveTtlIsRejected() {
        ReplyWindow.ofSeconds(0);
    }
}
