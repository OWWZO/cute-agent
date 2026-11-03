package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 统一文件引用快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolFileRef {

    private String fileName;

    private String relativePath;

    /** 以下 URL 常带签名/时效参数，禁止进入 toString。 */
    @ToString.Exclude
    private String downloadUrl;

    @ToString.Exclude
    private String previewUrl;

    @ToString.Exclude
    private String ossUrl;

    @ToString.Exclude
    private String domainUrl;

    private Long fileSize;

    private String mimeType;
}
