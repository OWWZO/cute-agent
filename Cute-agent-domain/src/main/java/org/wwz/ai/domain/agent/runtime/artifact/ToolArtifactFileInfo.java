package org.wwz.ai.domain.agent.runtime.artifact;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.File;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具产物到实时 resultMap.fileInfo 的唯一映射。
 */
public final class ToolArtifactFileInfo {

    private ToolArtifactFileInfo() {
    }

    public static List<Map<String, Object>> fromToolCall(AgentContext context, String toolCallId) {
        if (context == null || StringUtils.isBlank(toolCallId)) {
            return List.of();
        }
        return fromBindings(context.getArtifactBindingsByToolCallId(toolCallId));
    }

    public static List<Map<String, Object>> fromBindings(List<ToolArtifactBinding> bindings) {
        if (CollectionUtils.isEmpty(bindings)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolArtifactBinding binding : bindings) {
            // resultMap.fileInfo 是前端可见产物，内部中间文件不能进入主文件排序。
            if (binding == null || binding.getFile() == null || binding.isInternalFile()) {
                continue;
            }
            File file = binding.getFile();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", file.getFileName());
            item.put("ossUrl", file.getOssUrl());
            item.put("domainUrl", file.getDomainUrl());
            item.put("fileSize", file.getFileSize());
            result.add(item);
        }
        return result;
    }
}
