package org.wwz.ai.application.agent.stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.Map;

/**
 * 把 Case SSE 帧补齐成稳定时间线信封。
 *
 * <p>Live SSE 是瞬态传输层，只负责增强当前帧；持久化时间线只能由
 * Execution Ledger 写事务产生。</p>
 */
@Component
public class AgentTimelineFrameEnricher {

    /**
     * 只做内存增强；调用方必须在帧发布前、且按投影实例串行调用。
     */
    public TimelineItemView enrich(AgentSessionStreamFrame frame,
                                   AgentExecutionCommand request) {
        if (frame == null || request == null) {
            return null;
        }
        Map<String, Object> eventData = resolveEventData(frame);
        String eventType = resolveControlEventType(frame, eventData);
        if (eventType != null) {
            applyControlEvent(frame, request, eventType);
            return null;
        }

        if (eventData.isEmpty()) {
            return null;
        }
        if (!request.isTimelineRootSession()
                && TimelineItemProjection.isNestedSubAgentDetail(eventData)) {
            // 子 Agent 明细只属于自己的 session timeline；父流仍保留旧 SSE 字段供右侧工作区消费。
            return null;
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
        return item;
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
        frame.setTransientItem(true);
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

}
