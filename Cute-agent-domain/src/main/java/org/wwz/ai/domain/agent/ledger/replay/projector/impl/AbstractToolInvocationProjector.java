package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.wwz.ai.domain.agent.ledger.replay.ArtifactRelativePath;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactFormatter;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用历史回放投影基类。
 * <p>
 * 统一处理账本事实到前端 eventData 的外壳、artifact 合并、tool binding 和子 Agent 嵌套信息；
 * 具体工具只实现自身 payload 的解释，不直接读取数据库。
 */
@Slf4j
abstract class AbstractToolInvocationProjector implements ToolInvocationProjector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected Map<String, Object> readMap(String text) {
        if (StringUtils.isBlank(text)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(
                    text,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
            );
        } catch (Exception e) {
            log.warn("工具回放 JSON 解析失败，按空对象降级: text={}", StringUtils.abbreviate(text, 500), e);
            return new LinkedHashMap<>();
        }
    }

    protected List<Map<String, Object>> buildArtifactRefs(List<ArtifactView> artifacts) {
        if (CollectionUtils.isEmpty(artifacts)) {
            return List.of();
        }
        List<Map<String, Object>> refs = new ArrayList<>(artifacts.size());
        for (ArtifactView artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("resourceKey", StringUtils.defaultIfBlank(artifact.getStorageKey(), artifact.getFileName()));
            ref.put("name", artifact.getFileName());
            ref.put("previewUrl", artifact.getPreviewUrl());
            ref.put("downloadUrl", artifact.getDownloadUrl());
            ref.put("fileName", artifact.getFileName());
            ArtifactRelativePath.putOn(ref, artifact);
            ref.put("mimeType", artifact.getMimeType());
            ref.put("size", artifact.getFileSize());
            ref.put("missing", Boolean.FALSE);
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 将 typed output 里的逻辑 fileRefs 与 artifact 账本中的稳定链接进行合并。
     */
    protected List<Map<String, Object>> mergeFileRefs(List<ToolFileRef> fileRefs, List<ArtifactView> artifacts) {
        List<Map<String, Object>> merged = copyToolFileRefs(fileRefs);
        if (CollectionUtils.isEmpty(artifacts)) {
            return merged;
        }
        if (merged.isEmpty()) {
            return copyArtifactInfos(artifacts);
        }
        enrichFromArtifacts(merged, artifacts);
        markMissingLinks(merged);
        return merged;
    }

    private List<Map<String, Object>> copyToolFileRefs(List<ToolFileRef> fileRefs) {
        List<Map<String, Object>> merged = new ArrayList<>();
        if (CollectionUtils.isEmpty(fileRefs)) {
            return merged;
        }
        for (ToolFileRef fileRef : fileRefs) {
            if (fileRef != null) {
                merged.add(toToolFileInfo(fileRef));
            }
        }
        return merged;
    }

    private List<Map<String, Object>> copyArtifactInfos(List<ArtifactView> artifacts) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (ArtifactView artifact : artifacts) {
            if (artifact != null) {
                merged.add(toArtifactInfo(artifact));
            }
        }
        return merged;
    }

    private void enrichFromArtifacts(List<Map<String, Object>> merged, List<ArtifactView> artifacts) {
        for (Map<String, Object> info : merged) {
            String fileName = String.valueOf(info.getOrDefault("fileName", ""));
            ArtifactView matched = artifacts.stream()
                    .filter(artifact -> artifact != null && StringUtils.equals(fileName, artifact.getFileName()))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                continue;
            }
            info.putIfAbsent("downloadUrl", matched.getDownloadUrl());
            info.putIfAbsent("previewUrl", matched.getPreviewUrl());
            info.putIfAbsent("ossUrl", matched.getDownloadUrl());
            info.putIfAbsent("domainUrl", matched.getPreviewUrl());
            info.putIfAbsent("missing", Boolean.FALSE);
            ArtifactRelativePath.putOn(info, matched);
            if (matched.getFileSize() != null) {
                info.putIfAbsent("fileSize", matched.getFileSize());
            }
        }
    }

    private void markMissingLinks(List<Map<String, Object>> merged) {
        for (Map<String, Object> info : merged) {
            // String.valueOf(null) 会得到字面量 "null"，把「没有链接」误判成有链接。
            boolean hasPreview = hasTextValue(info.get("previewUrl"));
            boolean hasDownload = hasTextValue(info.get("downloadUrl"));
            if (!hasPreview && !hasDownload) {
                info.put("missing", Boolean.TRUE);
                info.putIfAbsent("missingReason", "artifact_not_found");
            } else {
                info.putIfAbsent("missing", Boolean.FALSE);
            }
        }
    }

    private boolean hasTextValue(Object value) {
        return value != null && StringUtils.isNotBlank(String.valueOf(value));
    }

    /**
     * 调用是否已落在非成功终态（失败/超时/停止/等待输入）。
     * <p>专用投影器必须先判它：结构化输出在成功和失败时可能是同一个类型，
     * 只看 output != null 会把失败回放成成功卡片。</p>
     */
    protected boolean isFailedInvocation(ToolInvocationView invocation) {
        return invocation != null
                && invocation.getStatus() != null
                && invocation.getStatus() != ExecutionLedgerConstants.STATUS_SUCCESS;
    }

    /**
     * 回退为通用 tool_result 投影。
     * <p>失败调用（或没有结构化输出的历史行）走这里，保证错误信息不会被专用卡片吞掉。</p>
     */
    protected List<ProjectedReplayEvent> projectAsToolResult(ToolInvocationView invocation,
                                                             List<ArtifactView> artifacts,
                                                             AgentStreamAccumulator state) {
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("toolName", invocation == null ? null : invocation.getToolName());
        toolResult.put("toolParam", invocation == null ? Map.of() : readMap(invocation.getInputJson()));
        toolResult.put("toolResult", resolveProjectedResultText(invocation));
        return List.of(buildTaskEvent(
                state,
                invocation,
                "tool_result",
                buildToolResultResponse(invocation, toolResult),
                buildArtifactRefs(artifacts)
        ));
    }

    /**
     * 失败优先展示 errorMsg（结构化错误码比 Error 前缀文本更有诊断价值），
     * 成功才取完整结果 JSON；历史行 toolResult 为 NULL 时回落到 llmObservation。
     */
    protected static String resolveProjectedResultText(ToolInvocationView invocation) {
        if (invocation == null) {
            return "";
        }
        if (isFailedStatus(invocation.getStatus())) {
            return StringUtils.defaultIfBlank(invocation.getErrorMsg(), invocation.resolveResultText());
        }
        return invocation.resolveResultText();
    }

    private static boolean isFailedStatus(Integer status) {
        return status != null && status != ExecutionLedgerConstants.STATUS_SUCCESS;
    }

    protected ProjectedReplayEvent buildTaskEvent(AgentStreamAccumulator state,
                                                  ToolInvocationView invocation,
                                                  String logicalMessageType,
                                                  Map<String, Object> responsePayload,
                                                  List<Map<String, Object>> artifactRefs) {
        String taskId = state.getTaskId();
        String orderKey = taskId + ":" + logicalMessageType;
        return ProjectedReplayEvent.builder()
                .taskId(taskId)
                .taskOrder(state.getTaskOrder().getAndIncrement())
                .messageId(resolveMessageId(invocation, logicalMessageType))
                .messageType("task")
                .messageOrder(state.getAndIncrOrder(orderKey))
                .resultMap(responsePayload)
                .artifactRefs(CollectionUtils.isEmpty(artifactRefs) ? null : artifactRefs)
                .timing(resolveReplayTiming(invocation))
                .build();
    }

    protected Map<String, Object> buildStructuredToolResponse(ToolInvocationView invocation,
                                                              String logicalMessageType,
                                                              Map<String, Object> resultMap) {
        decorateToolPayload(resultMap, invocation);
        Map<String, Object> response = newToolReplayEnvelope(invocation, logicalMessageType);
        response.put("resultMap", resultMap);
        // 内层 payload + 外层 envelope 都需要嵌套标签（对齐实时 SSE）
        mirrorNestingToOuter(response, invocation);
        return response;
    }

    protected Map<String, Object> buildToolResultResponse(ToolInvocationView invocation,
                                                          Map<String, Object> toolResult) {
        putToolBindingIfPresent(toolResult, invocation);
        Map<String, Object> response = newToolReplayEnvelope(invocation, "tool_result");
        response.put("toolResult", toolResult);
        mirrorNestingToOuter(response, invocation);
        // 镜像进 resultMap，对齐 BaseAgentStreamEventHandler 实时路径
        if (StringUtils.isNotBlank(invocation == null ? null : invocation.getParentToolCallId())) {
            Map<String, Object> nested = new LinkedHashMap<>();
            decorateToolPayload(nested, invocation);
            response.put("resultMap", nested);
        }
        return response;
    }

    /** 将嵌套标签同步到外层 envelope，供 resolveParentToolUseId 读取。 */
    private void mirrorNestingToOuter(Map<String, Object> response, ToolInvocationView invocation) {
        appendSubAgentNestingTags(response, invocation);
    }

    private Map<String, Object> newToolReplayEnvelope(ToolInvocationView invocation, String messageType) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", invocation == null ? null : invocation.getRequestId());
        response.put("messageId", resolveMessageId(invocation, messageType));
        response.put("messageTime", resolveMessageTime(invocation));
        response.put("messageType", messageType);
        response.put("isFinal", true);
        response.put("finish", false);
        return response;
    }

    private void decorateToolPayload(Map<String, Object> target, ToolInvocationView invocation) {
        putToolBindingIfPresent(target, invocation);
        appendSubAgentNestingTags(target, invocation);
    }

    protected void putToolBindingIfPresent(Map<String, Object> resultMap, ToolInvocationView invocation) {
        if (resultMap == null || invocation == null) {
            return;
        }
        if (StringUtils.isNotBlank(invocation.getToolCallId())) {
            resultMap.put("toolCallId", invocation.getToolCallId());
        }
        if (StringUtils.isNotBlank(invocation.getToolName())) {
            resultMap.put("toolName", invocation.getToolName());
        }
    }

    /**
     * 还原子 Agent 嵌套标签，与 SubAgentPrinter / BaseAgentStreamEventHandler 实时契约一致。
     */
    protected void appendSubAgentNestingTags(Map<String, Object> target, ToolInvocationView invocation) {
        if (target == null || invocation == null) {
            return;
        }
        if (StringUtils.isNotBlank(invocation.getParentToolCallId())) {
            target.put("parentToolUseId", invocation.getParentToolCallId());
        }
        if (StringUtils.isNotBlank(invocation.getSubAgentId())) {
            target.put("subAgentId", invocation.getSubAgentId());
        }
        String subAgentType = invocation.getSubAgentType();
        if (StringUtils.isBlank(subAgentType) && StringUtils.startsWith(invocation.getAgentName(), "subagent:")) {
            subAgentType = StringUtils.removeStart(invocation.getAgentName(), "subagent:");
        }
        if (StringUtils.isNotBlank(subAgentType)) {
            target.put("subAgentType", subAgentType);
        }
        if (StringUtils.isNotBlank(invocation.getSubAgentDescription())) {
            target.put("subAgentDescription", invocation.getSubAgentDescription());
        }
    }

    protected String resolveMessageId(ToolInvocationView invocation, String suffix) {
        String base = invocation == null ? null : invocation.getToolCallId();
        if (StringUtils.isBlank(base)) {
            base = invocation == null ? null : invocation.getToolName();
        }
        if (StringUtils.isBlank(base)) {
            base = "history-tool";
        }
        return StringUtils.isBlank(suffix) ? base : base + ":" + suffix;
    }

    protected String resolveMessageTime(ToolInvocationView invocation) {
        if (invocation == null) {
            return String.valueOf(System.currentTimeMillis());
        }
        if (invocation.getFinishedAt() != null) {
            return String.valueOf(invocation.getFinishedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (invocation.getStartedAt() != null) {
            return String.valueOf(invocation.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return String.valueOf(System.currentTimeMillis());
    }

    protected ReplayTiming resolveReplayTiming(ToolInvocationView invocation) {
        if (invocation == null) {
            return null;
        }
        java.time.LocalDateTime startedAt = invocation.getStartedAt();
        java.time.LocalDateTime finishedAt = invocation.getFinishedAt();
        Long durationMs = invocation.getDurationMs();
        String source = ReplayTiming.SOURCE_LEDGER;
        if (durationMs == null && startedAt != null && finishedAt != null) {
            durationMs = Duration.between(startedAt, finishedAt).toMillis();
            source = ReplayTiming.SOURCE_INFERRED;
        }
        if (startedAt == null && finishedAt == null && durationMs == null) {
            return null;
        }
        return ReplayTiming.builder()
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .durationMs(durationMs)
                .source(source)
                .build();
    }

    private Map<String, Object> toArtifactInfo(ArtifactView artifact) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fileName", artifact.getFileName());
        info.put("downloadUrl", artifact.getDownloadUrl());
        info.put("previewUrl", artifact.getPreviewUrl());
        info.put("ossUrl", artifact.getDownloadUrl());
        info.put("domainUrl", artifact.getPreviewUrl());
        info.put("missing", Boolean.FALSE);
        ArtifactRelativePath.putOn(info, artifact);
        if (artifact.getFileSize() != null) {
            info.put("fileSize", artifact.getFileSize());
        }
        return info;
    }

    private Map<String, Object> toToolFileInfo(ToolFileRef fileRef) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fileName", StringUtils.defaultString(fileRef.getFileName()));
        String relativePath = ToolArtifactFormatter.normalizeWorkspacePath(fileRef.getRelativePath());
        if (StringUtils.isNotBlank(relativePath)) {
            info.put("relativePath", relativePath);
            info.put("originFileName", relativePath);
        }
        if (StringUtils.isNotBlank(fileRef.getDownloadUrl())) {
            info.put("downloadUrl", fileRef.getDownloadUrl());
        }
        if (StringUtils.isNotBlank(fileRef.getPreviewUrl())) {
            info.put("previewUrl", fileRef.getPreviewUrl());
        }
        if (StringUtils.isNotBlank(fileRef.getOssUrl())) {
            info.put("ossUrl", fileRef.getOssUrl());
        }
        if (StringUtils.isNotBlank(fileRef.getDomainUrl())) {
            info.put("domainUrl", fileRef.getDomainUrl());
        }
        info.put("missing", Boolean.FALSE);
        if (fileRef.getFileSize() != null) {
            info.put("fileSize", fileRef.getFileSize());
        }
        return info;
    }
}
