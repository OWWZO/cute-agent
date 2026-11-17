package org.wwz.ai.domain.agent.ledger.timeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TimelineItemKeyTest {

    @Test
    public void shouldEncodeCanonicalIdentities() {
        assertEquals("llm:17", new TimelineItemKey.Llm(17).encode());
        assertEquals("llm:17:reasoning", new TimelineItemKey.LlmReasoning(17).encode());
        assertEquals("tool:call-1", new TimelineItemKey.Tool(" call-1 ").encode());
        assertEquals("artifact:23", new TimelineItemKey.Artifact(23).encode());
        assertEquals("run:req-1:final", new TimelineItemKey.RunFinal(" req-1 ").encode());
    }

    @Test
    public void shouldRejectInvalidIdentityInputs() {
        assertThrows(IllegalArgumentException.class, () -> new TimelineItemKey.Llm(0));
        assertThrows(IllegalArgumentException.class, () -> new TimelineItemKey.Tool(" "));
        assertThrows(IllegalArgumentException.class, () -> new TimelineItemKey.Artifact(-1));
        assertThrows(IllegalArgumentException.class, () -> new TimelineItemKey.RunFinal(null));
    }
}
