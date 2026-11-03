package org.wwz.ai.domain.agent.runtime.dto;

import lombok.Builder;
import lombok.Value;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;

/**
 * Agent 文件值对象，统一表示输入附件和生成 artifact 的稳定引用。
 * <p>不可变：实例注册进 {@code ToolArtifactRegistry} 后不允许再改写，
 * 否则以其为元素的 {@code ToolArtifactBinding} 哈希会漂移。</p>
 * <p>{@code relativePath} 在构造时归一化：绝对路径、盘符、{@code ..} 段等
 * 无法证明落在工作区内的值会被清空，避免下游按根目录解析时逃逸。</p>
 */
@Value
public class File {
    private String ossUrl;
    private String domainUrl;
    private String fileName;
    private Integer fileSize;
    private String description;
    private String originFileName;
    /** 工作区内相对路径，可含目录，如 report/index.html */
    private String relativePath;
    private String originOssUrl;
    private String originDomainUrl;
    private Boolean isInternalFile;

    @Builder
    public File(String ossUrl,
                String domainUrl,
                String fileName,
                Integer fileSize,
                String description,
                String originFileName,
                String relativePath,
                String originOssUrl,
                String originDomainUrl,
                Boolean isInternalFile) {
        this.ossUrl = ossUrl;
        this.domainUrl = domainUrl;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.description = description;
        this.originFileName = originFileName;
        this.relativePath = WorkspaceRelativePath.sanitize(relativePath);
        this.originOssUrl = originOssUrl;
        this.originDomainUrl = originDomainUrl;
        this.isInternalFile = isInternalFile;
    }
}
