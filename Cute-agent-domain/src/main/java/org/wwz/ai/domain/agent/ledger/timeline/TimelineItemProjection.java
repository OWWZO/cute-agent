package org.wwz.ai.domain.agent.ledger.timeline;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 live canonical event 或 history replay event 投影成统一时间线项。
 *
 * <p>这里只解释稳定事件语义；完整内容原样放入 payload，不写回目录表。</p>
 */
public final class TimelineItemProjection {

    public static final String PROTOCOL_VERSION = "3";

    private TimelineItemProjection() {
    }

    public static boolean hasRenderableEventData(List<TimelineItemView> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (TimelineItemView item : items) {
            if (item != null && item.getPayload() != null
                    && item.getPayload().get("eventData") instanceof Map<?, ?>) {
                return true;
            }
        }
        return false;
    }

    public static TimelineItemView projectEvent(String runId,
                                                String sessionId,
                                                long seq,
                                                Map<String, Object> eventData,
                                                String frameStatus,
                                                boolean finished) {
        Map<String, Object> event = eventData == null ? Map.of() : eventData;
        Map<String, Object> task = firstMap(event.get("resultMap"), event);
        Map<String, Object> nested = firstMap(task.get("resultMap"), Map.of());
        String logicalType = resolveLogicalType(event);
        String messageId = firstText(task.get("messageId"), event.get("messageId"));
        String requestId = firstText(task.get("requestId"), event.get("requestId"), runId);
        String toolCallId = firstText(
                task.get("toolCallId"),
                nested.get("toolCallId"),
                event.get("toolCallId")
        );
        String llmInvocationId = firstText(
                task.get("llmInvocationId"),
                nested.get("llmInvocationId"),
                event.get("llmInvocationId")
        );
        String childSessionId = firstText(
                task.get("childSessionId"),
                nested.get("childSessionId"),
                event.get("childSessionId")
        );
        String subAgentId = firstText(
                task.get("subAgentId"),
                nested.get("subAgentId"),
                event.get("subAgentId")
        );
        Object artifactIdValue = firstNonBlankObject(
                task.get("artifactId"),
                nested.get("artifactId"),
                event.get("artifactId")
        );
        String artifactId = text(artifactIdValue);
        Object toolInvocationIdValue = firstNonBlankObject(
                task.get("toolInvocationId"),
                nested.get("toolInvocationId"),
                event.get("toolInvocationId")
        );
        String parentToolCallId = firstText(
                task.get("parentToolUseId"),
                task.get("parentToolCallId"),
                nested.get("parentToolUseId"),
                nested.get("parentToolCallId"),
                event.get("parentToolUseId"),
                event.get("parentToolCallId")
        );
        String explicitItemId = firstText(
                task.get("timelineItemId"),
                nested.get("timelineItemId"),
                event.get("timelineItemId")
        );

        TimelineItemKind kind = resolveKind(logicalType);
        boolean nestedSubAgent = isNestedSubAgentDetail(event);
        String itemId = StringUtils.defaultIfBlank(
                explicitItemId,
                resolveItemId(logicalType, requestId, messageId, toolCallId,
                        llmInvocationId, childSessionId, subAgentId, artifactId,
                        parentToolCallId)
        );
        String parentItemId = resolveParentItemId(explicitItemId, parentToolCallId);
        if (parentItemId == null
                && kind == TimelineItemKind.ARTIFACT
                && StringUtils.isNotBlank(toolCallId)) {
            parentItemId = "tool:" + toolCallId;
        }
        TimelineItemStatus status = resolveStatus(logicalType, task, nested, frameStatus, finished);
        String eventType = resolveEventType(logicalType, status, nestedSubAgent);
        String agentId = StringUtils.defaultIfBlank(subAgentId, "main");
        String delta = resolveDelta(logicalType, task, nested);
        int subOrder = intValue(firstObject(task.get("messageOrder"), event.get("messageOrder")), 0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventData", event);
        if (artifactIdValue != null) {
            payload.put("artifactId", artifactIdValue);
        }
        if (toolInvocationIdValue != null) {
            payload.put("toolInvocationId", toolInvocationIdValue);
        }
        if (StringUtils.isNotBlank(llmInvocationId)) {
            payload.put("llmInvocationId", llmInvocationId);
        }

        return TimelineItemView.builder()
                .protocolVersion(PROTOCOL_VERSION)
                .eventType(eventType)
                .eventId(eventId(sessionId, seq, null))
                .runId(requestId)
                .sessionId(sessionId)
                .seq(seq)
                .eventSeq(seq)
                .itemId(itemId)
                .parentItemId(parentItemId)
                .agentId(agentId)
                .kind(kind.name())
                .phase(resolvePhase(logicalType, nestedSubAgent).name())
                .status(status.name())
                .orderKey(seq)
                .subOrder(subOrder)
                .delta(delta)
                .payload(payload)
                .errorMsg(firstText(task.get("errorMsg"), nested.get("errorMsg"), event.get("errorMsg")))
                .build();
    }

    /**
     * 用窄表目录修正顺序、状态和父子关系。目录中的非零 orderKey 优先且永不被回放覆盖。
     */
    public static List<TimelineItemView> mergeDirectory(List<TimelineItemView> views,
                                                        List<TimelineItem> directory) {
        List<TimelineItemView> result = new ArrayList<>(views == null ? List.of() : views);
        Map<TimelineItemView, Long> naturalOrder = new IdentityHashMap<>();
        for (TimelineItemView view : result) {
            naturalOrder.put(view, view.getOrderKey() > 0
                    ? view.getOrderKey()
                    : Math.max(1L, view.getSeq()));
        }
        Map<String, TimelineItem> byItemId = new LinkedHashMap<>();
        if (directory != null) {
            for (TimelineItem item : directory) {
                if (item != null && StringUtils.isNotBlank(item.getItemId())) {
                    byItemId.put(item.getItemId(), item);
                }
            }
        }
        Set<TimelineItemView> matchedViews = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> matchedItemIds = new HashSet<>();
        for (TimelineItemView view : result) {
            TimelineItem item = byItemId.get(view.getItemId());
            if (item != null) {
                applyDirectory(view, item);
                matchedViews.add(view);
                matchedItemIds.add(view.getItemId());
            }
        }
        byItemId.keySet().removeAll(matchedItemIds);
        Set<String> projectedFileCalls = new HashSet<>();
        Set<String> projectedArtifactIds = new HashSet<>();
        for (TimelineItemView view : result) {
            if (!TimelineItemKind.ARTIFACT.name().equals(view.getKind())) {
                continue;
            }
            String projectedArtifactId = payloadText(view, "artifactId");
            if (StringUtils.isNotBlank(projectedArtifactId)) {
                projectedArtifactIds.add(projectedArtifactId);
            }
            String projectedToolCallId = resolveViewToolCallId(view);
            if (StringUtils.isNotBlank(projectedToolCallId)) {
                projectedFileCalls.add(projectedToolCallId);
            }
        }
        for (TimelineItem item : byItemId.values()) {
            if (TimelineItemKind.ARTIFACT.name().equals(item.getKind())) {
                // 没有 artifact 主键或父 Tool 的目录行是旧 live projector 留下的孤儿。
                // 它们不能合成 File 卡，否则一个真实产物会被伪造成多个“文件”。
                if (StringUtils.isBlank(text(item.getArtifactId()))
                        || StringUtils.isBlank(parentToolCallId(item.getParentItemId()))
                        || item.getToolInvocationId() == null
                        || item.getToolInvocationId() <= 0) {
                    continue;
                }
                if (StringUtils.isNotBlank(text(item.getArtifactId()))
                        && projectedArtifactIds.contains(text(item.getArtifactId()))) {
                    continue;
                }
                String toolCallId = parentToolCallId(item.getParentItemId());
                if (StringUtils.isNotBlank(toolCallId) && projectedFileCalls.contains(toolCallId)) {
                    continue;
                }
                TimelineItemView directoryView = fromDirectory(item);
                result.add(directoryView);
                matchedViews.add(directoryView);
                continue;
            }
            if (TimelineItemKind.HITL.name().equals(item.getKind())
                    || TimelineItemPhase.FINAL.name().equals(item.getPhase())) {
                TimelineItemView directoryView = fromDirectory(item);
                result.add(directoryView);
                matchedViews.add(directoryView);
            }
        }
        alignUnmatchedOrder(result, matchedViews, naturalOrder);
        result.sort(Comparator
                .comparingLong((TimelineItemView view) -> view.getOrderKey() <= 0 ? Long.MAX_VALUE : view.getOrderKey())
                .thenComparingInt(TimelineItemView::getSubOrder)
                .thenComparing(TimelineItemView::getItemId, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public static TimelineItemView fromDirectory(TimelineItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", item.getArtifactId());
        payload.put("toolInvocationId", item.getToolInvocationId());
        boolean terminal = TimelineItemStatus.COMPLETED.name().equals(item.getStatus())
                || TimelineItemStatus.FAILED.name().equals(item.getStatus())
                || TimelineItemStatus.CANCELLED.name().equals(item.getStatus());
        long seq = item.getUpdatedSeq() == null ? 0 : item.getUpdatedSeq();
        return TimelineItemView.builder()
                .protocolVersion(PROTOCOL_VERSION)
                .eventType(terminal ? "item.completed" : "item.upserted")
                .eventId(eventId(item.getSessionId(), seq, null))
                .runId(item.getRunId())
                .sessionId(item.getSessionId())
                .seq(seq)
                .eventSeq(0L)
                .itemId(item.getItemId())
                .parentItemId(item.getParentItemId())
                .agentId(item.getAgentId())
                .kind(item.getKind())
                .phase(item.getPhase())
                .status(item.getStatus())
                .orderKey(item.getOrderKey() == null ? 0 : item.getOrderKey())
                .subOrder(item.getSubOrder() == null ? 0 : item.getSubOrder())
                .payload(payload)
                .transientItem(false)
                .build();
    }

    /**
     * 子 Agent 的普通输出挂在父工具卡下，不进入父 run 的主时间线。
     * {@code subagent_start/end} 是父链路的摘要帧，仍然保留。
     */
    public static boolean isNestedSubAgentDetail(Map<String, Object> eventData) {
        Map<String, Object> event = eventData == null ? Map.of() : eventData;
        Map<String, Object> task = firstMap(event.get("resultMap"), event);
        Map<String, Object> nested = firstMap(task.get("resultMap"), Map.of());
        String logicalType = resolveLogicalType(event);
        if ("subagent_start".equals(logicalType) || "subagent_end".equals(logicalType)) {
            return false;
        }
        String parentToolCallId = firstText(
                task.get("parentToolUseId"),
                task.get("parentToolCallId"),
                nested.get("parentToolUseId"),
                nested.get("parentToolCallId"),
                event.get("parentToolUseId"),
                event.get("parentToolCallId")
        );
        String subAgentId = firstText(
                task.get("subAgentId"),
                nested.get("subAgentId"),
                event.get("subAgentId")
        );
        return StringUtils.isNotBlank(parentToolCallId) || StringUtils.isNotBlank(subAgentId);
    }

    private static void applyDirectory(TimelineItemView view, TimelineItem item) {
        if (item.getOrderKey() != null && item.getOrderKey() > 0) {
            view.setOrderKey(item.getOrderKey());
        }
        if (item.getSubOrder() != null && item.getSubOrder() != 0) {
            view.setSubOrder(item.getSubOrder());
        }
        if (item.getUpdatedSeq() != null && item.getUpdatedSeq() > 0) {
            view.setSeq(item.getUpdatedSeq());
            view.setEventId(item.getSessionId() + ":" + item.getUpdatedSeq());
        }
        view.setItemId(StringUtils.defaultIfBlank(item.getItemId(), view.getItemId()));
        view.setParentItemId(StringUtils.defaultIfBlank(item.getParentItemId(), view.getParentItemId()));
        view.setAgentId(StringUtils.defaultIfBlank(item.getAgentId(), view.getAgentId()));
        view.setKind(StringUtils.defaultIfBlank(item.getKind(), view.getKind()));
        view.setPhase(StringUtils.defaultIfBlank(item.getPhase(), view.getPhase()));
        view.setStatus(mergeStatus(view.getStatus(), item.getStatus()));
    }

    private static String mergeStatus(String eventStatus, String directoryStatus) {
        TimelineItemStatus current = status(eventStatus);
        TimelineItemStatus stored = status(directoryStatus);
        if (stored == null) {
            return current == null ? eventStatus : current.name();
        }
        if (current == null) {
            return stored.name();
        }
        if (isTerminal(stored)) {
            return stored.name();
        }
        if (isTerminal(current)) {
            return current.name();
        }
        // 非终态由目录决定；这样 WAITING_INPUT -> RUNNING 的续跑不会被旧视图卡住。
        return stored.name();
    }

    private static boolean isTerminal(TimelineItemStatus status) {
        return status == TimelineItemStatus.COMPLETED
                || status == TimelineItemStatus.CANCELLED
                || status == TimelineItemStatus.FAILED;
    }

    public static String resolveSourceType(String kind, String phase) {
        if (TimelineItemPhase.FINAL.name().equals(phase)) {
            return TimelineItemSourceType.RUN.name();
        }
        if (TimelineItemKind.TOOL_CALL.name().equals(kind)
                || TimelineItemKind.SUBAGENT.name().equals(kind)
                || TimelineItemKind.HITL.name().equals(kind)) {
            return TimelineItemSourceType.TOOL.name();
        }
        if (TimelineItemKind.ARTIFACT.name().equals(kind)) {
            return TimelineItemSourceType.ARTIFACT.name();
        }
        if (TimelineItemKind.ASSISTANT_MESSAGE.name().equals(kind)
                || TimelineItemKind.REASONING.name().equals(kind)
                || TimelineItemKind.PLAN.name().equals(kind)) {
            return TimelineItemSourceType.LLM.name();
        }
        return TimelineItemSourceType.SYSTEM.name();
    }

    private static TimelineItemStatus status(String value) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(value));
        try {
            return StringUtils.isBlank(normalized) ? null : TimelineItemStatus.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static TimelineItemKind resolveKind(String logicalType) {
        String type = StringUtils.defaultString(logicalType);
        if ("llm_reasoning".equals(type)) {
            return TimelineItemKind.REASONING;
        }
        if ("tool_call".equals(type)
                || "tool_call_delta".equals(type)
                || "tool_result".equals(type)
                || "deep_search".equals(type)
                || "browser".equals(type)
                || "browser_viewport".equals(type)
                || "code".equals(type)
                || "data_analysis".equals(type)
                || "knowledge".equals(type)
                || "html".equals(type)
                || "markdown".equals(type)
                || "ppt".equals(type)) {
            return TimelineItemKind.TOOL_CALL;
        }
        if ("file".equals(type)) {
            return TimelineItemKind.ARTIFACT;
        }
        if ("plan".equals(type) || "plan_thought".equals(type)) {
            return TimelineItemKind.PLAN;
        }
        if ("ask_user_question".equals(type)
                || "desktop_control".equals(type)
                || "plan_approval".equals(type)) {
            return TimelineItemKind.HITL;
        }
        if ("subagent_start".equals(type)
                || "subagent_end".equals(type)
                || "subagent_progress".equals(type)) {
            return TimelineItemKind.SUBAGENT;
        }
        if ("session_tasks".equals(type)
                || "user_brief".equals(type)
                || "user_inject".equals(type)
                || "llm_retry".equals(type)
                || "context_usage".equals(type)
                || "plan_mode_entered".equals(type)
                || "stream_settle".equals(type)
                || "heartbeat".equals(type)
                || "follow_pending".equals(type)
                || "follow_idle".equals(type)
                || "ui_tree".equals(type)
                || "ui_patch".equals(type)
                || "task".equals(type)) {
            return TimelineItemKind.SYSTEM_NOTICE;
        }
        return TimelineItemKind.ASSISTANT_MESSAGE;
    }

    private static String resolveLogicalType(Map<String, Object> event) {
        Map<String, Object> task = firstMap(event.get("resultMap"), event);
        String outerType = text(event.get("messageType"));
        return "task".equals(outerType)
                ? StringUtils.defaultIfBlank(text(task.get("messageType")), outerType)
                : StringUtils.defaultIfBlank(outerType, text(task.get("messageType")));
    }

    private static boolean isHitl(String logicalType) {
        return "ask_user_question".equals(logicalType)
                || "desktop_control".equals(logicalType)
                || "plan_approval".equals(logicalType);
    }

    private static String resolveItemId(String logicalType,
                                        String requestId,
                                        String messageId,
                                        String toolCallId,
                                        String llmInvocationId,
                                        String childSessionId,
                                        String subAgentId,
                                        String artifactId,
                                        String parentToolCallId) {
        if ("result".equals(logicalType)) {
            if (StringUtils.isNotBlank(parentToolCallId) || StringUtils.isNotBlank(subAgentId)) {
                return "subagent:" + StringUtils.defaultIfBlank(
                        firstText(subAgentId, parentToolCallId, requestId, messageId), "unknown") + ":final";
            }
            return "run:" + StringUtils.defaultIfBlank(
                    firstText(requestId, messageId), "unknown") + ":final";
        }
        if ("task_summary".equals(logicalType)) {
            return "summary:" + StringUtils.defaultIfBlank(
                    firstText(llmInvocationId, messageId, requestId), "unknown");
        }
        if ("ask_user_question".equals(logicalType)
                || "desktop_control".equals(logicalType)
                || "plan_approval".equals(logicalType)) {
            return "hitl:" + StringUtils.defaultIfBlank(
                    firstText(toolCallId, messageId), "unknown");
        }
        if ("subagent_start".equals(logicalType)
                || "subagent_end".equals(logicalType)
                || "subagent_progress".equals(logicalType)) {
            return "subagent:" + StringUtils.defaultIfBlank(
                    firstText(childSessionId, subAgentId, messageId), "unknown");
        }
        if ("file".equals(logicalType)) {
            return "artifact:" + StringUtils.defaultIfBlank(
                    firstText(artifactId, toolCallId, messageId), "unknown");
        }
        if ("plan".equals(logicalType) || "plan_thought".equals(logicalType)) {
            String base = StringUtils.defaultIfBlank(
                    firstText(llmInvocationId, messageId), "unknown");
            return "plan_thought".equals(logicalType)
                    ? "plan:" + base + ":thought"
                    : "plan:" + base;
        }
        if ("agent_stream".equals(logicalType)
                || "tool_thought".equals(logicalType)
                || "llm_reasoning".equals(logicalType)) {
            String base = StringUtils.isNotBlank(llmInvocationId)
                    ? "llm:" + llmInvocationId
                    : "llm:" + StringUtils.defaultIfBlank(messageId, "unknown");
            return "llm_reasoning".equals(logicalType) ? base + ":reasoning" : base;
        }
        if (StringUtils.isNotBlank(toolCallId)) {
            return "tool:" + toolCallId;
        }
        return "item:" + StringUtils.defaultIfBlank(messageId, "unknown");
    }

    private static String resolveParentItemId(String explicitItemId, String parentToolCallId) {
        if (StringUtils.isBlank(parentToolCallId)) {
            return null;
        }
        if (StringUtils.isNotBlank(explicitItemId) && explicitItemId.equals("tool:" + parentToolCallId)) {
            return null;
        }
        return "tool:" + parentToolCallId;
    }

    private static TimelineItemStatus resolveStatus(String logicalType,
                                                    Map<String, Object> task,
                                                    Map<String, Object> nested,
                                                    String frameStatus,
                                                    boolean finished) {
        String clientEvidence = StringUtils.lowerCase(String.join(" ",
                text(task.get("status")),
                text(nested.get("status"))
        ));
        TimelineItemStatus fromItem = statusFromEvidence(clientEvidence);
        if (fromItem == null) {
            String persistenceEvidence = StringUtils.lowerCase(String.join(" ",
                    text(task.get("persistenceStatus")),
                    text(nested.get("persistenceStatus"))
            ));
            fromItem = statusFromEvidence(persistenceEvidence);
        }
        if (fromItem != null) {
            return fromItem;
        }
        if (isHitl(logicalType)) {
            // HITL 让步帧的 finish=true / status=success 只表示传输层可以收口，
            // 卡片是否还在等待用户必须由卡片自身的 payload 状态决定。
            return TimelineItemStatus.WAITING_INPUT;
        }
        TimelineItemStatus fromFrame = statusFromEvidence(
                StringUtils.lowerCase(StringUtils.defaultString(frameStatus)));
        if (fromFrame != null) {
            return fromFrame;
        }
        if ("tool_result".equals(logicalType)
                || "file".equals(logicalType)
                || "result".equals(logicalType)
                || "task_summary".equals(logicalType)
                || finished) {
            return TimelineItemStatus.COMPLETED;
        }
        return TimelineItemStatus.RUNNING;
    }

    private static TimelineItemStatus statusFromEvidence(String evidence) {
        if (evidence.contains("fail") || evidence.contains("error") || evidence.contains("timeout")) {
            return TimelineItemStatus.FAILED;
        }
        if (evidence.contains("stop") || evidence.contains("cancel")) {
            return TimelineItemStatus.CANCELLED;
        }
        if (evidence.contains("wait") || evidence.contains("pending")) {
            return TimelineItemStatus.WAITING_INPUT;
        }
        if (evidence.contains("running") || evidence.contains("in_progress")) {
            return TimelineItemStatus.RUNNING;
        }
        if (evidence.contains("success")
                || evidence.contains("complete")
                || evidence.contains("done")
                || evidence.contains("answer")
                || evidence.contains("decid")
                || evidence.contains("approv")) {
            return TimelineItemStatus.COMPLETED;
        }
        return null;
    }

    private static String resolveEventType(String logicalType,
                                           TimelineItemStatus status,
                                           boolean nestedSubAgent) {
        if ("result".equals(logicalType)) {
            if (nestedSubAgent) {
                return "item.completed";
            }
            return switch (status) {
                case FAILED -> "run.failed";
                case CANCELLED -> "run.stopped";
                default -> "run.completed";
            };
        }
        if ("task_summary".equals(logicalType)) {
            return "item.completed";
        }
        if (status == TimelineItemStatus.COMPLETED
                || status == TimelineItemStatus.FAILED
                || status == TimelineItemStatus.CANCELLED) {
            return "item.completed";
        }
        if (isStreamingType(logicalType)) {
            return "item.delta";
        }
        return "item.upserted";
    }

    private static boolean isStreamingType(String logicalType) {
        return "agent_stream".equals(logicalType)
                || "tool_thought".equals(logicalType)
                || "llm_reasoning".equals(logicalType)
                || "tool_call".equals(logicalType)
                || "tool_call_delta".equals(logicalType)
                || "deep_search".equals(logicalType)
                || "browser".equals(logicalType)
                || "code".equals(logicalType)
                || "data_analysis".equals(logicalType)
                || "knowledge".equals(logicalType)
                || "subagent_progress".equals(logicalType);
    }

    private static TimelineItemPhase resolvePhase(String logicalType, boolean nestedSubAgent) {
        if ("result".equals(logicalType)) {
            return nestedSubAgent ? TimelineItemPhase.INTERMEDIATE : TimelineItemPhase.FINAL;
        }
        if ("task_summary".equals(logicalType)) {
            return TimelineItemPhase.SUMMARY;
        }
        return TimelineItemPhase.INTERMEDIATE;
    }

    private static String resolveDelta(String logicalType,
                                       Map<String, Object> task,
                                       Map<String, Object> nested) {
        if ("llm_reasoning".equals(logicalType)) {
            return firstText(task.get("reasoningContent"), nested.get("reasoningContent"));
        }
        if ("tool_thought".equals(logicalType)) {
            return firstText(task.get("toolThought"), nested.get("toolThought"));
        }
        if ("agent_stream".equals(logicalType)) {
            return firstText(task.get("result"), nested.get("result"));
        }
        return null;
    }

    private static String payloadText(TimelineItemView view, String key) {
        if (view == null || view.getPayload() == null) {
            return null;
        }
        return text(view.getPayload().get(key));
    }

    private static String resolveViewToolCallId(TimelineItemView view) {
        if (view == null || view.getPayload() == null) {
            return null;
        }
        Object raw = view.getPayload().get("eventData");
        if (!(raw instanceof Map<?, ?> eventRaw)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) eventRaw;
        Map<String, Object> task = firstMap(event.get("resultMap"), event);
        Map<String, Object> nested = firstMap(task.get("resultMap"), Map.of());
        return firstText(
                task.get("toolCallId"),
                nested.get("toolCallId"),
                event.get("toolCallId")
        );
    }

    private static String parentToolCallId(String parentItemId) {
        return StringUtils.startsWith(parentItemId, "tool:")
                ? StringUtils.substringAfter(parentItemId, "tool:")
                : null;
    }

    /**
     * live orderKey 是 session 级 SSE seq，history 回放是 run 内 seq。
     * 无目录行的通知类项按已匹配目录邻居归位，避免整段历史被顶到最前面。
     */
    private static void alignUnmatchedOrder(List<TimelineItemView> views,
                                            Set<TimelineItemView> anchoredViews,
                                            Map<TimelineItemView, Long> naturalOrder) {
        List<TimelineItemView> ordered = new ArrayList<>(views);
        ordered.sort(Comparator
                .comparingLong((TimelineItemView view) -> naturalOrder.getOrDefault(
                        view,
                        view.getOrderKey() > 0 ? view.getOrderKey() : Math.max(1L, view.getSeq())))
                .thenComparing(TimelineItemView::getItemId, Comparator.nullsLast(String::compareTo)));
        long cursor = 1L;
        for (int i = 0; i < ordered.size(); i++) {
            TimelineItemView view = ordered.get(i);
            if (anchoredViews.contains(view)) {
                if (view.getOrderKey() <= 0) {
                    // 0 表示目录尚未分配 orderKey，历史投影应按自然顺序补一个可用值，
                    // 否则最终排序会把它和后面的未匹配项一起推到时间线末尾。
                    view.setOrderKey(cursor);
                }
                cursor = Math.max(cursor, view.getOrderKey() + 1);
                continue;
            }
            int groupStart = i;
            while (i + 1 < ordered.size()
                    && !anchoredViews.contains(ordered.get(i + 1))) {
                i++;
            }
            int groupSize = i - groupStart + 1;
            TimelineItemView previous = previousAnchor(ordered, groupStart, anchoredViews);
            TimelineItemView next = nextAnchor(ordered, i, anchoredViews);
            if (previous != null && previous.getOrderKey() > 0) {
                for (int offset = 0; offset < groupSize; offset++) {
                    TimelineItemView current = ordered.get(groupStart + offset);
                    current.setOrderKey(previous.getOrderKey());
                    current.setSubOrder(previous.getSubOrder() + 1 + offset);
                }
            } else if (next != null) {
                long firstOrder = next.getOrderKey() - groupSize;
                for (int offset = 0; offset < groupSize; offset++) {
                    TimelineItemView current = ordered.get(groupStart + offset);
                    if (firstOrder >= 1) {
                        current.setOrderKey(firstOrder + offset);
                    } else {
                        // next 前没有足够整数序号时，只能借用 next 的 orderKey，
                        // 用更小的 subOrder 保持“排在 next 之前”，避免与 next 并列。
                        current.setOrderKey(next.getOrderKey());
                        current.setSubOrder(next.getSubOrder() - groupSize + offset);
                    }
                }
            } else {
                for (int offset = 0; offset < groupSize; offset++) {
                    TimelineItemView current = ordered.get(groupStart + offset);
                    if (current.getOrderKey() <= 0) {
                        current.setOrderKey(cursor++);
                    }
                }
            }
        }
    }

    private static TimelineItemView previousAnchor(List<TimelineItemView> views,
                                                   int index,
                                                   Set<TimelineItemView> anchoredViews) {
        for (int i = index - 1; i >= 0; i--) {
            if (anchoredViews.contains(views.get(i))) {
                return views.get(i);
            }
        }
        return null;
    }

    private static TimelineItemView nextAnchor(List<TimelineItemView> views,
                                               int index,
                                               Set<TimelineItemView> anchoredViews) {
        for (int i = index + 1; i < views.size(); i++) {
            if (anchoredViews.contains(views.get(i))) {
                return views.get(i);
            }
        }
        return null;
    }

    public static String eventId(String sessionId, long seq, String suffix) {
        String scope = StringUtils.defaultIfBlank(sessionId, "run");
        String base = scope + ":" + seq;
        return StringUtils.isBlank(suffix) ? base : base + ":" + suffix;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMap(Object first, Object fallback) {
        if (first instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (fallback instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = text(value);
            if (StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Object firstObject(Object first, Object fallback) {
        return first == null ? fallback : first;
    }

    private static Object firstNonBlankObject(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (StringUtils.isNotBlank(text(value))) {
                return value;
            }
        }
        return null;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
