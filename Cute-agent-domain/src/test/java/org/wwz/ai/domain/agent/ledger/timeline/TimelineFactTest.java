package org.wwz.ai.domain.agent.ledger.timeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TimelineFactTest {

    @Test
    public void artifactMustHaveArtifactIdAndToolParent() {
        ArtifactTimelineFact fact = new ArtifactTimelineFact(
                new TimelineItemKey.Artifact(23),
                "session-1",
                "req-1",
                "main",
                "tool:call-1",
                7L
        );

        assertEquals("artifact:23", fact.key().encode());
        assertEquals("tool:call-1", fact.parentItemId());
        assertEquals(TimelineItemStatus.COMPLETED, fact.status());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactTimelineFact(
                null, "session-1", "req-1", "main", "tool:call-1", 7L));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactTimelineFact(
                new TimelineItemKey.Artifact(23), "session-1", "req-1", "main", null, 7L));
    }

    @Test
    public void toolMustHaveToolCallId() {
        ToolTimelineFact fact = new ToolTimelineFact(
                new TimelineItemKey.Tool("call-1"),
                "session-1",
                "req-1",
                "main",
                TimelineItemStatus.RUNNING,
                null,
                7L
        );

        assertEquals("tool:call-1", fact.key().encode());
        assertThrows(IllegalArgumentException.class, () -> new ToolTimelineFact(
                null, "session-1", "req-1", "main", TimelineItemStatus.RUNNING, null, 7L));
    }

    @Test
    public void onlyTerminalRunCanBecomeRunFinal() {
        RunTimelineFact fact = new RunTimelineFact(
                new TimelineItemKey.RunFinal("req-1"),
                "session-1",
                "req-1",
                "main",
                TimelineItemStatus.COMPLETED
        );

        assertEquals(TimelineItemPhase.FINAL, fact.phase());
        assertThrows(IllegalArgumentException.class, () -> new RunTimelineFact(
                new TimelineItemKey.RunFinal("req-1"),
                "session-1",
                "req-1",
                "main",
                TimelineItemStatus.RUNNING));
    }

    @Test
    public void llmFactCannotClaimFinalPhase() {
        assertThrows(IllegalArgumentException.class, () -> new LlmTimelineFact(
                new TimelineItemKey.Llm(17),
                "session-1",
                "req-1",
                "main",
                TimelineItemPhase.FINAL,
                TimelineItemStatus.COMPLETED,
                null));
    }
}
