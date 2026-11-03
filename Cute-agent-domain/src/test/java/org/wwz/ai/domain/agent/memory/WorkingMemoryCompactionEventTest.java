package org.wwz.ai.domain.agent.memory;

import org.junit.Assert;
import org.junit.Test;

/**
 * 审计快照在构造期截断，避免单行数百 KB 的 JSON 落库。
 */
public class WorkingMemoryCompactionEventTest {

    @Test
    public void oversizedPayloadsAreTruncatedAtConstruction() {
        String huge = "{\"message\":\"" + "x".repeat(100_000) + "\"}";

        WorkingMemoryCompactionEvent event = WorkingMemoryCompactionEvent.builder()
                .sessionId("s-1")
                .beforeMessagesJson(huge)
                .afterMessagesJson(huge)
                .build();

        Assert.assertTrue(event.getBeforeMessagesJson().length()
                <= WorkingMemoryCompactionEvent.MAX_PAYLOAD_CHARS + "...[truncated]".length());
        Assert.assertTrue(event.getBeforeMessagesJson().endsWith("...[truncated]"));
        Assert.assertTrue(event.getAfterMessagesJson().endsWith("...[truncated]"));
    }

    @Test
    public void smallPayloadsArePreserved() {
        WorkingMemoryCompactionEvent event = WorkingMemoryCompactionEvent.builder()
                .beforeMessagesJson("[]")
                .afterMessagesJson("[{\"role\":\"user\"}]")
                .build();

        Assert.assertEquals("[]", event.getBeforeMessagesJson());
        Assert.assertEquals("[{\"role\":\"user\"}]", event.getAfterMessagesJson());
    }
}
