package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;

/**
 * 统一文件引用快照。
 * <p>{@code relativePath} 可能来自工具返回或历史元数据，构造/赋值时统一归一化：
 * 绝对路径、盘符、{@code ..} 段等无法证明落在工作区内的值会被清空。</p>
 */
@Data
@NoArgsConstructor
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

    @Builder
    public ToolFileRef(String fileName,
                       String relativePath,
                       String downloadUrl,
                       String previewUrl,
                       String ossUrl,
                       String domainUrl,
                       Long fileSize,
                       String mimeType) {
        this.fileName = fileName;
        this.relativePath = WorkspaceRelativePath.sanitize(relativePath);
        this.downloadUrl = downloadUrl;
        this.previewUrl = previewUrl;
        this.ossUrl = ossUrl;
        this.domainUrl = domainUrl;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
    }

    /** Lombok 不再生成该 setter：确保任何赋值路径都经过同一套校验。 */
    public void setRelativePath(String relativePath) {
        this.relativePath = WorkspaceRelativePath.sanitize(relativePath);
    }
}
