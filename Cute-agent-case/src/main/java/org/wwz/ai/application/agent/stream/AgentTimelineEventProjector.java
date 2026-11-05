package org.wwz.ai.application.agent.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemKind;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.Map;
import java.util.Set;

/**
 * 把 Case SSE 帧补齐成稳定时间线事件，并同步更新窄目录表。
 *
 * <p>流式 delta 只在首次出现时写一次目录；后续 delta 只走 SSE，不逐帧写数据库。</p>
 */
@Slf4j
@Component
public class AgentTimelineEventProjector {

    private final ITimelineItemRepository timelineItemRepository;

    public AgentTimelineEventProjector(ITimelineItemRepository timelineItemRepository) {
        this.timelineItemRepository = timelineItemRepository;
    }

    @Autowired
    public AgentTimelineEventProjector(ObjectProvider<ITimelineItemRepository> timelineItemRepositoryProvider) {
        this.timelineItemRepository = timelineItemRepositoryProvider == null
                ? null
                : timelineItemRepositoryProvider.getIfAvailable();
    }

    public void project(AgentSessionStreamFrame frame,
                        AgentExecutionCommand request,
                        Set<String> writtenItems) {
        if (frame == null || request == null) {
            return;
        }
        Map<String, Object> eventData = resolveEventData(frame);
        String eventType = resolveControlEventType(frame, eventData);
        if (eventType != null) {
            applyControlEvent(frame, request, eventType);
            return;
        }

        if (eventData.isEmpty()) {
            return;
        }
        if (TimelineItemProjection.isNestedSubAgentDetail(eventData)) {
            // 子 Agent 明细只属于自己的 session timeline；父流仍保留旧 SSE 字段供右侧工作区消费。
            return;
        }
        TimelineItemView item = TimelineItemProjection.projectEvent(
                request.getRequestId(),
                request.getSessionId(),
                frame.getEventSeq(),
                eventData,
                frame.getStatus(),
                frame.isFinished()
        );
        applyItem(frame, item);
        persistIfNeeded(item, writtenItems);
    }

    private void persistIfNeeded(TimelineItemView item, Set<String> writtenItems) {
        if (timelineItemRepository == null
                || item == null || StringUtils.isBlank(item.getItemId())
                || StringUtils.isBlank(item.getSessionId())
                || StringUtils.isBlank(item.getRunId())
                || TimelineItemKind.SYSTEM_NOTICE.name().equals(item.getKind())) {
            return;
        }
        if (writtenItems == null) {
            return;
        }
        boolean firstTouch = writtenItems.add(item.getItemId());
        boolean stateChange = !"item.delta".equals(item.getEventType());
        if (!firstTouch && !stateChange) {
            return;
        }
        try {
            timelineItemRepository.upsert(TimelineItem.builder()
                    .sessionId(item.getSessionId())
                    .runId(item.getRunId())
                    .itemId(item.getItemId())
                    .parentItemId(item.getParentItemId())
                    .agentId(item.getAgentId())
                    .kind(item.getKind())
                    .phase(item.getPhase())
                    .status(item.getStatus())
                    .orderKey(item.getOrderKey())
                    .subOrder(item.getSubOrder())
                    .updatedSeq(item.getSeq())
                    .sourceType(TimelineItemProjection.resolveSourceType(
                            item.getKind(), item.getPhase()))
                    .llmInvocationId(payloadLong(item.getPayload(), "llmInvocationId"))
                    .toolInvocationId(payloadLong(item.getPayload(), "toolInvocationId"))
                    .artifactId(payloadLong(item.getPayload(), "artifactId"))
                    .build());
        } catch (Exception e) {
            writtenItems.remove(item.getItemId());
            log.warn("timeline directory update failed, runId={}, itemId={}",
                    item.getRunId(), item.getItemId(), e);
        }
    }

    private String resolveControlEventType(AgentSessionStreamFrame frame,
                                           Map<String, Object> eventData) {
        String logicalType = resolveLogicalMessageType(eventData);
        String controlType = switch (logicalType) {
            case "heartbeat" -> "stream.heartbeat";
            case "follow_pending" -> "stream.follow_pending";
            case "follow_idle" -> "stream.follow_idle";
            case "stream_settle" -> "stream.closed";
            case "run_wake" -> "run.wake";
            case "run_wake_failed" -> "run.wake_failed";
            default -> null;
        };
        if (controlType != null) {
            return controlType;
        }
        String packageType = StringUtils.trimToEmpty(frame.getPackageType());
        return switch (packageType) {
            case "heartbeat" -> "stream.heartbeat";
            case "follow_pending" -> "stream.follow_pending";
            case "follow_idle" -> "stream.follow_idle";
            case "stream_settle" -> "stream.closed";
            case "run_wake" -> "run.wake";
            case "run_wake_failed" -> "run.wake_failed";
            default -> null;
        };
    }

    private String resolveLogicalMessageType(Map<String, Object> eventData) {
        if (eventData == null || eventData.isEmpty()) {
            return "";
        }
        Object nested = eventData.get("resultMap");
        if (nested instanceof Map<?, ?> map && map.get("messageType") != null) {
            return StringUtils.trimToEmpty(String.valueOf(map.get("messageType")));
        }
        return StringUtils.trimToEmpty(String.valueOf(eventData.getOrDefault("messageType", "")));
    }

    private void applyControlEvent(AgentSessionStreamFrame frame,
                                   AgentExecutionCommand request,
                                   String eventType) {
        frame.setProtocolVersion(TimelineItemProjection.PROTOCOL_VERSION);
        frame.setEventType(eventType);
        frame.setEventId(TimelineItemProjection.eventId(request.getSessionId(), frame.getEventSeq(), null));
        frame.setSeq(frame.getEventSeq());
        frame.setRunId(request.getRequestId());
    }

    private void applyItem(AgentSessionStreamFrame frame, TimelineItemView item) {
        frame.setProtocolVersion(item.getProtocolVersion());
        frame.setEventType(item.getEventType());
        frame.setEventId(item.getEventId());
        frame.setSeq(item.getSeq());
        frame.setRunId(item.getRunId());
        frame.setItemId(item.getItemId());
        frame.setParentItemId(item.getParentItemId());
        frame.setAgentId(item.getAgentId());
        frame.setKind(item.getKind());
        frame.setPhase(item.getPhase());
        frame.setItemStatus(item.getStatus());
        frame.setOrderKey(item.getOrderKey());
        frame.setSubOrder(item.getSubOrder());
        frame.setDelta(item.getDelta());
        frame.setPayload(compactPayload(item.getPayload(), frame.getResultMap()));
        if (StringUtils.isNotBlank(item.getErrorMsg())
                && ("run.failed".equals(item.getEventType()) || "run.stopped".equals(item.getEventType()))) {
            frame.setErrorMsg(item.getErrorMsg());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveEventData(AgentSessionStreamFrame frame) {
        if (frame.getResultMap() == null) {
            return Map.of();
        }
        Object eventData = frame.getResultMap().get("eventData");
        if (eventData instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return frame.getResultMap();
    }

    /**
     * live SSE 已经在 resultMap.eventData 携带完整内容，payload 只补充目录附加信息。
     */
    private Map<String, Object> compactPayload(Map<String, Object> payload,
                                               Map<String, Object> resultMap) {
        if (payload == null || payload.isEmpty() || resultMap == null) {
            return payload;
        }
        Object resultEventData = resultMap.get("eventData");
        Object payloadEventData = payload.get("eventData");
        boolean duplicated = payloadEventData != null
                && (payloadEventData == resultEventData
                || (resultEventData == null && payloadEventData == resultMap));
        if (!duplicated) {
            return payload;
        }
        Map<String, Object> compact = new java.util.LinkedHashMap<>(payload);
        compact.remove("eventData");
        return compact.isEmpty() ? null : compact;
    }

    private Long payloadLong(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
