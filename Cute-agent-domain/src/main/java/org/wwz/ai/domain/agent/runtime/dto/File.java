package org.wwz.ai.domain.agent.runtime.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Agent 文件值对象，统一表示输入附件和生成 artifact 的稳定引用。
 * <p>不可变：实例注册进 {@code ToolArtifactRegistry} 后不允许再改写，
 * 否则以其为元素的 {@code ToolArtifactBinding} 哈希会漂移。</p>
 */
@Value
@Builder
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
}
