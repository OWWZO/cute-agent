package org.wwz.ai.domain.agent.ledger.timeline;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LedgerTimelineProjectionWriterTest {

    @Test
    public void artifactBatchMustKeepArtifactIdentityAndToolParent() {
        ITimelineItemRepository timelineRepository = Mockito.mock(ITimelineItemRepository.class);
        IExecutionLedgerWriteRepository ledgerRepository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-1", "tool:call-1"))
                .thenReturn(TimelineItem.builder()
                        .sessionId("session-1")
                        .itemId("tool:call-1")
                        .orderKey(41L)
                        .subOrder(0)
                        .build());
        LedgerTimelineProjectionWriter writer =
                new LedgerTimelineProjectionWriter(timelineRepository, ledgerRepository);

        writer.writeArtifacts(List.of(
                new ArtifactTimelineFact(
                        new TimelineItemKey.Artifact(23),
                        "session-1",
                        "req-1",
                        "main",
                        "tool:call-1",
                        7L),
                new ArtifactTimelineFact(
                        new TimelineItemKey.Artifact(24),
                        "session-1",
                        "req-1",
                        "main",
                        "tool:call-1",
                        7L)
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(timelineRepository).upsertBatch(captor.capture());
        List<TimelineItem> items = captor.getValue();
        assertEquals(2, items.size());
        assertEquals("artifact:23", items.get(0).getItemId());
        assertEquals("tool:call-1", items.get(0).getParentItemId());
        assertEquals(Long.valueOf(23L), items.get(0).getArtifactId());
        assertEquals(Long.valueOf(7L), items.get(0).getToolInvocationId());
        assertEquals(Long.valueOf(41L), items.get(0).getOrderKey());
        assertEquals(Integer.valueOf(1), items.get(0).getSubOrder());
        assertEquals(Integer.valueOf(2), items.get(1).getSubOrder());
        Mockito.verify(ledgerRepository, Mockito.never())
                .allocateTimelineOrder("session-1", 1);
    }

    @Test
    public void artifactOrderMustNotLeakAcrossSessionsWithTheSameToolCallId() {
        ITimelineItemRepository timelineRepository = Mockito.mock(ITimelineItemRepository.class);
        IExecutionLedgerWriteRepository ledgerRepository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-1", "tool:call-1"))
                .thenReturn(TimelineItem.builder()
                        .sessionId("session-1")
                        .itemId("tool:call-1")
                        .orderKey(41L)
                        .build());
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-2", "tool:call-1"))
                .thenReturn(TimelineItem.builder()
                        .sessionId("session-2")
                        .itemId("tool:call-1")
                        .orderKey(81L)
                        .build());
        LedgerTimelineProjectionWriter writer =
                new LedgerTimelineProjectionWriter(timelineRepository, ledgerRepository);

        writer.writeArtifacts(List.of(
                new ArtifactTimelineFact(
                        new TimelineItemKey.Artifact(23),
                        "session-1",
                        "req-1",
                        "child-a",
                        "tool:call-1",
                        7L),
                new ArtifactTimelineFact(
                        new TimelineItemKey.Artifact(24),
                        "session-2",
                        "req-2",
                        "child-b",
                        "tool:call-1",
                        8L)
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(timelineRepository).upsertBatch(captor.capture());
        List<TimelineItem> items = captor.getValue();
        assertEquals(Long.valueOf(41L), items.get(0).getOrderKey());
        assertEquals(Long.valueOf(81L), items.get(1).getOrderKey());
    }

    @Test
    public void runFinalMustBeWrittenAsFinalAssistantMessage() {
        ITimelineItemRepository timelineRepository = Mockito.mock(ITimelineItemRepository.class);
        IExecutionLedgerWriteRepository ledgerRepository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-1", "run:req-1:final"))
                .thenReturn(null);
        Mockito.when(ledgerRepository.allocateTimelineOrder("session-1", 1)).thenReturn(80L);
        LedgerTimelineProjectionWriter writer =
                new LedgerTimelineProjectionWriter(timelineRepository, ledgerRepository);

        writer.writeRunFinal(new RunTimelineFact(
                new TimelineItemKey.RunFinal("req-1"),
                "session-1",
                "req-1",
                "main",
                TimelineItemStatus.COMPLETED));

        ArgumentCaptor<TimelineItem> captor = ArgumentCaptor.forClass(TimelineItem.class);
        Mockito.verify(timelineRepository).upsert(captor.capture());
        TimelineItem item = captor.getValue();
        assertEquals("run:req-1:final", item.getItemId());
        assertEquals("FINAL", item.getPhase());
        assertEquals("RUN", item.getSourceType());
        assertTrue(item.getParentItemId() == null);
    }

    @Test
    public void llmReasoningMustShareItsMessageOrderAndPrecedeItAsSibling() {
        ITimelineItemRepository timelineRepository = Mockito.mock(ITimelineItemRepository.class);
        IExecutionLedgerWriteRepository ledgerRepository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-1", "llm:7"))
                .thenReturn(TimelineItem.builder()
                        .sessionId("session-1")
                        .itemId("llm:7")
                        .orderKey(61L)
                        .subOrder(0)
                        .build());
        Mockito.when(timelineRepository.queryBySessionAndItemId("session-1", "llm:7:reasoning"))
                .thenReturn(null);
        LedgerTimelineProjectionWriter writer =
                new LedgerTimelineProjectionWriter(timelineRepository, ledgerRepository);

        writer.writeLlmFinished(new LlmTimelineFact(
                new TimelineItemKey.LlmReasoning(7L),
                "session-1",
                "req-1",
                "main",
                TimelineItemPhase.INTERMEDIATE,
                TimelineItemStatus.COMPLETED,
                "llm:7"));

        ArgumentCaptor<TimelineItem> captor = ArgumentCaptor.forClass(TimelineItem.class);
        Mockito.verify(timelineRepository).upsert(captor.capture());
        TimelineItem item = captor.getValue();
        assertEquals("llm:7:reasoning", item.getItemId());
        assertEquals("llm:7", item.getParentItemId());
        assertEquals(Long.valueOf(61L), item.getOrderKey());
        assertEquals(Integer.valueOf(-1), item.getSubOrder());
    }
}
