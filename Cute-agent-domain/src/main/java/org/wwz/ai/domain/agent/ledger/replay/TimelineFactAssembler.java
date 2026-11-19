package org.wwz.ai.domain.agent.ledger.replay;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemKind;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 Ledger 事实补齐 committed TimelineItem 的可渲染载荷。
 *
 * <p>目录行只保存身份和父子关系；正文、文件地址等业务载荷在这里按
 * artifactId / run-final 身份读取，不把第二套内容写回目录表。</p>
 */
public final class TimelineFactAssembler {

    private TimelineFactAssembler() {
    }

    public static void enrich(List<TimelineItemView> views,
                              DialogueRunView run,
                              List<ArtifactView> artifacts) {
        if (views == null || views.isEmpty()) {
            return;
        }
        Map<Long, ArtifactView> artifactById = new HashMap<>();
        if (artifacts != null) {
            for (ArtifactView artifact : artifacts) {
                if (artifact != null && artifact.getId() != null) {
                    artifactById.put(artifact.getId(), artifact);
                }
            }
        }
        for (TimelineItemView view : views) {
            if (view == null) {
                continue;
            }
            if (TimelineItemKind.ARTIFACT.name().equals(view.getKind())) {
                ArtifactView artifact = artifactById.get(artifactId(view));
                if (artifact != null) {
                    enrichArtifact(view, artifact);
                }
                continue;
            }
            if (isRunFinal(view)) {
                enrichRunFinal(view, run);
            }
        }
    }

    private static Long artifactId(TimelineItemView view) {
        Object value = view.getPayload() == null ? null : view.getPayload().get("artifactId");
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

    private static boolean isRunFinal(TimelineItemView view) {
        String itemId = StringUtils.trimToEmpty(view.getItemId());
        return itemId.startsWith("run:") && itemId.endsWith(":final");
    }

    private static void enrichRunFinal(TimelineItemView view, DialogueRunView run) {
        if (run == null || StringUtils.isBlank(run.getFinalSummaryText())
                || hasEventData(view)) {
            return;
        }
        payload(view).put("eventData", RunFinalEventAssembler.eventData(
                run,
                run.getRequestId() + "-summary",
                1,
                1
        ));
    }

    private static boolean hasEventData(TimelineItemView view) {
        return view.getPayload() != null && view.getPayload().get("eventData") instanceof Map<?, ?>;
    }

    private static void enrichArtifact(TimelineItemView view, ArtifactView artifact) {
        Map<String, Object> payload = payload(view);
        payload.put("artifactId", artifact.getId());
        if (artifact.getToolInvocationId() != null) {
            payload.put("toolInvocationId", artifact.getToolInvocationId());
        }
        payload.put("fileName", artifact.getFileName());
        payload.put("downloadUrl", artifact.getDownloadUrl());
        payload.put("previewUrl", artifact.getPreviewUrl());
        payload.put("mimeType", artifact.getMimeType());
        payload.put("fileSize", artifact.getFileSize());
        payload.put("storageKey", artifact.getStorageKey());
        Map<String, Object> eventData = ensureArtifactEventData(view);
        Map<String, Object> writableTask = writableMap(eventData.get("resultMap"));
        putArtifactFields(writableTask, artifact, view);
        Object rawNested = writableTask.get("resultMap");
        if (rawNested instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> writableNested = (Map<String, Object>) nested;
            putArtifactFields(writableNested, artifact, view);
        }
        eventData.put("artifactRefs", List.of(artifactFileInfo(artifact)));
    }

    private static Map<String, Object> ensureArtifactEventData(TimelineItemView view) {
        Map<String, Object> payload = payload(view);
        Object rawEventData = payload.get("eventData");
        if (rawEventData instanceof Map<?, ?> existing) {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = (Map<String, Object>) existing;
            if (!(eventData.get("resultMap") instanceof Map<?, ?>)) {
                eventData.put("resultMap", new LinkedHashMap<String, Object>());
            }
            return eventData;
        }
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageType", "task");
        eventData.put("messageId", view.getItemId());
        eventData.put("taskId", view.getItemId());
        eventData.put("taskOrder", view.getOrderKey());
        eventData.put("messageOrder", view.getSubOrder());
        eventData.put("resultMap", new LinkedHashMap<String, Object>());
        payload.put("eventData", eventData);
        return eventData;
    }

    private static Map<String, Object> writableMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) map;
            return writable;
        }
        Map<String, Object> writable = new LinkedHashMap<>();
        return writable;
    }

    private static void putArtifactFields(Map<String, Object> target,
                                          ArtifactView artifact,
                                          TimelineItemView view) {
        target.putIfAbsent("messageId", view.getItemId());
        target.putIfAbsent("messageType", "file");
        target.putIfAbsent("requestId", view.getRunId());
        target.putIfAbsent("status", "success");
        target.putIfAbsent("finish", true);
        target.putIfAbsent("isFinal", true);
        target.putIfAbsent("artifactId", artifact.getId());
        Object toolInvocationId = artifact.getToolInvocationId() != null
                ? artifact.getToolInvocationId()
                : view.getPayload() == null ? null : view.getPayload().get("toolInvocationId");
        if (toolInvocationId != null) {
            target.putIfAbsent("toolInvocationId", toolInvocationId);
        }
        String toolCallId = parentToolCallId(view.getParentItemId());
        if (StringUtils.isNotBlank(toolCallId)) {
            target.putIfAbsent("toolCallId", toolCallId);
            target.putIfAbsent("parentToolCallId", toolCallId);
            target.putIfAbsent("parentToolUseId", toolCallId);
        }
        putIfBlank(target, "fileName", artifact.getFileName());
        putIfBlank(target, "downloadUrl", artifact.getDownloadUrl());
        putIfBlank(target, "previewUrl", artifact.getPreviewUrl());
        putIfBlank(target, "mimeType", artifact.getMimeType());
        putIfBlank(target, "fileSize", artifact.getFileSize());
        putIfBlank(target, "storageKey", artifact.getStorageKey());
        if (!(target.get("fileInfo") instanceof List<?>)) {
            target.put("fileInfo", List.of(artifactFileInfo(artifact)));
        }
        if (!(target.get("artifactRefs") instanceof List<?>)) {
            target.put("artifactRefs", List.of(artifactFileInfo(artifact)));
        }
    }

    private static void putIfBlank(Map<String, Object> target, String key, Object value) {
        Object existing = target.get(key);
        if (value != null && (existing == null || StringUtils.isBlank(String.valueOf(existing)))) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> payload(TimelineItemView view) {
        if (view.getPayload() == null) {
            view.setPayload(new LinkedHashMap<>());
        } else if (!(view.getPayload() instanceof LinkedHashMap)) {
            // 兼容只读/不可变 payload，避免幂等回放时原地写入失败。
            view.setPayload(new LinkedHashMap<>(view.getPayload()));
        }
        return view.getPayload();
    }

    private static String parentToolCallId(String parentItemId) {
        return StringUtils.startsWith(parentItemId, "tool:")
                ? StringUtils.substringAfter(parentItemId, "tool:")
                : null;
    }

    private static Map<String, Object> artifactFileInfo(ArtifactView artifact) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("artifactId", artifact.getId());
        file.put("resourceKey", StringUtils.defaultIfBlank(
                artifact.getStorageKey(), artifact.getFileName()));
        file.put("name", artifact.getFileName());
        file.put("fileName", artifact.getFileName());
        file.put("displayName", artifact.getFileName());
        file.put("previewUrl", artifact.getPreviewUrl());
        file.put("downloadUrl", artifact.getDownloadUrl());
        file.put("mimeType", artifact.getMimeType());
        file.put("fileSize", artifact.getFileSize());
        file.put("missing", Boolean.FALSE);
        ArtifactRelativePath.putOn(file, artifact);
        return file;
    }
}
