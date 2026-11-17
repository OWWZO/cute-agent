package org.wwz.ai.domain.agent.ledger.timeline;

import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 Ledger 事务内把类型化事实物化为时间线目录项。
 */
@Component
public class LedgerTimelineProjectionWriter implements TimelineProjectionWriter {

    private final ITimelineItemRepository timelineItemRepository;
    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;

    public LedgerTimelineProjectionWriter(ITimelineItemRepository timelineItemRepository,
                                          IExecutionLedgerWriteRepository executionLedgerWriteRepository) {
        this.timelineItemRepository = timelineItemRepository;
        this.executionLedgerWriteRepository = executionLedgerWriteRepository;
    }

    @Override
    public void writeLlmStarted(LlmTimelineFact fact) {
        writeSingle(fact, 0);
    }

    @Override
    public void writeLlmFinished(LlmTimelineFact fact) {
        if (fact != null && fact.key() instanceof TimelineItemKey.LlmReasoning) {
            String parentItemId = new TimelineItemKey.Llm(
                    ((TimelineItemKey.LlmReasoning) fact.key()).invocationId()).encode();
            long orderKey = existingOrderKey(fact.sessionId(), parentItemId);
            writeItem(fact, orderKey, -1);
            return;
        }
        writeSingle(fact, 0);
    }

    @Override
    public void writeToolStarted(ToolTimelineFact fact) {
        writeSingle(fact, 0);
    }

    @Override
    public void writeToolFinished(ToolTimelineFact fact) {
        writeSingle(fact, 0);
    }

    @Override
    public void writeArtifacts(List<ArtifactTimelineFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Map<String, List<ArtifactTimelineFact>> byParent = new LinkedHashMap<>();
        for (ArtifactTimelineFact fact : facts) {
            if (fact != null) {
                String groupKey = fact.sessionId() + "\0" + fact.parentItemId();
                byParent.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(fact);
            }
        }
        List<TimelineItem> items = new ArrayList<>(facts.size());
        for (List<ArtifactTimelineFact> siblings : byParent.values()) {
            ArtifactTimelineFact first = siblings.get(0);
            long orderKey = existingOrderKey(first.sessionId(), first.parentItemId());
            for (int i = 0; i < siblings.size(); i++) {
                items.add(toItem(siblings.get(i), orderKey, i + 1));
            }
        }
        writeBatch(items);
    }

    @Override
    public void writeRunFinal(RunTimelineFact fact) {
        writeSingle(fact, 0);
    }

    private void writeSingle(TimelineFact fact, int subOrder) {
        if (fact == null) {
            return;
        }
        long orderKey = existingOrderKey(fact.sessionId(), fact.key().encode());
        writeItem(fact, orderKey, subOrder);
    }

    private void writeItem(TimelineFact fact, long orderKey, int subOrder) {
        if (fact == null) {
            return;
        }
        upsert(toItem(fact, orderKey, subOrder));
    }

    private long existingOrderKey(String sessionId, String itemId) {
        TimelineItem existing = timelineItemRepository.queryBySessionAndItemId(sessionId, itemId);
        if (existing != null && existing.getOrderKey() != null && existing.getOrderKey() > 0) {
            return existing.getOrderKey();
        }
        return allocateOrder(sessionId, 1);
    }

    private long allocateOrder(String sessionId, int count) {
        return executionLedgerWriteRepository.allocateTimelineOrder(sessionId, count);
    }

    private TimelineItem toItem(TimelineFact fact, long orderKey, int subOrder) {
        return TimelineItem.builder()
                .sessionId(fact.sessionId())
                .runId(fact.runId())
                .itemId(fact.key().encode())
                .parentItemId(fact.parentItemId())
                .agentId(fact.agentId())
                .kind(fact.kind().name())
                .phase(fact.phase().name())
                .status(fact.status().name())
                .orderKey(orderKey)
                .subOrder(subOrder)
                .updatedSeq(0L)
                .sourceType(resolveSourceType(fact.key()).name())
                .llmInvocationId(resolveLlmInvocationId(fact.key()))
                .toolInvocationId(resolveToolInvocationId(fact))
                .artifactId(resolveArtifactId(fact.key()))
                .build();
    }

    private void upsert(TimelineItem item) {
        try {
            timelineItemRepository.upsert(item);
        } catch (Exception e) {
            throw new TimelineItemWriteException("timeline item write failed: " + item.getItemId(), e);
        }
    }

    private void writeBatch(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            timelineItemRepository.upsertBatch(items);
        } catch (Exception e) {
            throw new TimelineItemWriteException("timeline item batch write failed", e);
        }
    }

    private TimelineItemSourceType resolveSourceType(TimelineItemKey key) {
        if (key instanceof TimelineItemKey.Llm || key instanceof TimelineItemKey.LlmReasoning) {
            return TimelineItemSourceType.LLM;
        }
        if (key instanceof TimelineItemKey.Tool) {
            return TimelineItemSourceType.TOOL;
        }
        if (key instanceof TimelineItemKey.Artifact) {
            return TimelineItemSourceType.ARTIFACT;
        }
        return TimelineItemSourceType.RUN;
    }

    private Long resolveLlmInvocationId(TimelineItemKey key) {
        if (key instanceof TimelineItemKey.Llm llm) {
            return llm.invocationId();
        }
        if (key instanceof TimelineItemKey.LlmReasoning reasoning) {
            return reasoning.invocationId();
        }
        return null;
    }

    private Long resolveToolInvocationId(TimelineFact fact) {
        if (fact instanceof ToolTimelineFact toolFact) {
            return toolFact.toolInvocationId();
        }
        if (fact instanceof ArtifactTimelineFact artifactFact) {
            return artifactFact.toolInvocationId();
        }
        return null;
    }

    private Long resolveArtifactId(TimelineItemKey key) {
        return key instanceof TimelineItemKey.Artifact artifact ? artifact.artifactId() : null;
    }
}
