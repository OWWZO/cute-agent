package org.wwz.ai.domain.agent.runtime.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Agent runtime 使用的稳定文件引用。
 * <p>HTTP 上传字段在进入 runtime 前由 Trigger/Case mapper 转换为该模型。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionFile {

    private String fileName;
    private String fileDesc;
    /** 以下 URL 常带签名参数且会过期，禁止进入 toString，也不参与对象身份判定。 */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String ossUrl;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String domainUrl;
    private Integer fileSize;
    private String fileType;
    private String resourceKey;
    private String mimeType;
    private String originFileName;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String originFileUrl;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String originOssUrl;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String originDomainUrl;
}
