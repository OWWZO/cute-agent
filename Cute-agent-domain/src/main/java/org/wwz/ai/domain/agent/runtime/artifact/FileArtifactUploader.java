package org.wwz.ai.domain.agent.runtime.artifact;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.FileArtifactPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.FileRequest;
import org.wwz.ai.domain.agent.runtime.dto.FileResponse;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 内部文件产物上传适配器。
 * <p>
 * 仅供 deep_search、multimodalagent 等运行时工具落盘产物使用，不注册为 Agent 工具。
 * </p>
 */
@Slf4j
public final class FileArtifactUploader {

    private final AgentContext agentContext;

    public FileArtifactUploader(AgentContext agentContext) {
        this.agentContext = Objects.requireNonNull(agentContext, "agentContext must not be null");
    }

    public ToolResultPayload upload(FileRequest request,
                                    boolean internalFile,
                                    ToolArtifactSource artifactSource) {
        try {
            if (request == null) {
                return ToolResultPayload.failureFrom("文件上传请求为空", null);
            }
            CuteConfig config = agentContext.requireRuntimeDependencies().requireCuteConfig();
            FileArtifactPort fileArtifactPort = agentContext.requireRuntimeDependencies().requireFileArtifactPort();

            request.setRequestId(StringUtils.defaultIfBlank(
                    agentContext.getSessionId(), agentContext.getRequestId()));
            String fileName = StringUtil.removeSpecialChars(request.getFileName()).trim();
            if (fileName.isEmpty()) {
                fileName = "autogen_file";
            }
            if (!fileName.contains(".")) {
                fileName = fileName + ".md";
            }
            request.setFileName(fileName);

            FileResponse response = fileArtifactPort.upload(
                    config.getCodeInterpreterUrl(), request);
            if (response == null) {
                return ToolResultPayload.failureFrom("文件上传失败 " + fileName, null);
            }

            if (artifactSource != null) {
                agentContext.registerGeneratedArtifact(artifactSource, File.builder()
                        .fileName(fileName)
                        .fileSize(response.getFileSize())
                        .ossUrl(response.getOssUrl())
                        .domainUrl(response.getDomainUrl())
                        .description(request.getDescription())
                        .isInternalFile(internalFile)
                        .build());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", Boolean.TRUE);
            data.put("fileName", fileName);
            if (response.getFileSize() != null) {
                data.put("fileSize", response.getFileSize());
            }
            // 即使没有 artifactSource，也把远端地址回传，避免上传成功却无处引用。
            if (StringUtils.isNotBlank(response.getOssUrl())) {
                data.put("ossUrl", response.getOssUrl());
            }
            if (StringUtils.isNotBlank(response.getDomainUrl())) {
                data.put("domainUrl", response.getDomainUrl());
            }
            return ToolResultPayload.fromData(data);
        } catch (Exception e) {
            log.warn("internal file artifact upload failed, requestId={}",
                    agentContext.getRequestId(), e);
            String detail = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            return ToolResultPayload.failureFrom("文件上传失败: " + detail, null);
        }
    }

}
