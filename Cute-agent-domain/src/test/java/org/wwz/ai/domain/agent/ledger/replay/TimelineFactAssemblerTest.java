package org.wwz.ai.domain.agent.ledger.replay;

import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemStatus;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimelineFactAssemblerTest {

    @Test
    public void artifactAndRunFinalPayloadsComeFromLedgerIdentity() {
        TimelineItemView artifact = TimelineItemProjection.fromDirectory(
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("artifact:23")
                        .parentItemId("tool:call-1")
                        .kind("ARTIFACT")
                        .phase("INTERMEDIATE")
                        .status(TimelineItemStatus.COMPLETED.name())
                        .artifactId(23L)
                        .toolInvocationId(7L)
                        .orderKey(11L)
                        .build());
        TimelineItemView runFinal = TimelineItemProjection.fromDirectory(
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("run:req-1:final")
                        .kind("ASSISTANT_MESSAGE")
                        .phase("FINAL")
                        .status(TimelineItemStatus.COMPLETED.name())
                        .orderKey(12L)
                        .build());

        TimelineFactAssembler.enrich(
                List.of(artifact, runFinal),
                DialogueRunView.builder()
                        .requestId("req-1")
                        .finalSummaryText("最终结论")
                        .build(),
                List.of(ArtifactView.builder()
                        .id(23L)
                        .fileName("report.md")
                        .downloadUrl("https://files/report.md")
                        .mimeType("text/markdown")
                        .build()));

        assertEquals("report.md", artifact.getPayload().get("fileName"));
        Map<?, ?> artifactTask = (Map<?, ?>) ((Map<?, ?>) artifact.getPayload().get("eventData"))
                .get("resultMap");
        assertEquals("file", artifactTask.get("messageType"));
        assertEquals("report.md", artifactTask.get("fileName"));
        assertEquals(23L, artifactTask.get("artifactId"));
        assertEquals(7L, artifactTask.get("toolInvocationId"));
        assertEquals("call-1", artifactTask.get("toolCallId"));
        assertEquals("call-1", artifactTask.get("parentToolCallId"));
        assertEquals(1, ((List<?>) artifactTask.get("fileInfo")).size());

        Map<?, ?> finalTask = (Map<?, ?>) ((Map<?, ?>) runFinal.getPayload().get("eventData"))
                .get("resultMap");
        assertEquals("result", finalTask.get("messageType"));
        assertEquals("最终结论", finalTask.get("result"));
        assertTrue(runFinal.getPayload().containsKey("eventData"));
    }

    @Test
    public void artifactWithoutLedgerRowStaysUnrenderable() {
        TimelineItemView artifact = TimelineItemProjection.fromDirectory(
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("artifact:23")
                        .parentItemId("tool:call-1")
                        .kind("ARTIFACT")
                        .phase("INTERMEDIATE")
                        .status(TimelineItemStatus.COMPLETED.name())
                        .artifactId(23L)
                        .toolInvocationId(7L)
                        .orderKey(11L)
                        .build());

        TimelineFactAssembler.enrich(List.of(artifact), null, List.of());

        assertEquals(null, artifact.getPayload().get("eventData"));
    }

    @Test
    public void nonRunFinalPhaseCannotReceiveFinalPayload() {
        TimelineItemView summary = TimelineItemProjection.fromDirectory(
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("summary:1")
                        .kind("ASSISTANT_MESSAGE")
                        .phase("FINAL")
                        .status(TimelineItemStatus.COMPLETED.name())
                        .orderKey(12L)
                        .build());

        TimelineFactAssembler.enrich(
                List.of(summary),
                DialogueRunView.builder()
                        .requestId("req-1")
                        .finalSummaryText("最终结论")
                        .build(),
                List.of());

        assertEquals(null, summary.getPayload().get("eventData"));
    }
}
