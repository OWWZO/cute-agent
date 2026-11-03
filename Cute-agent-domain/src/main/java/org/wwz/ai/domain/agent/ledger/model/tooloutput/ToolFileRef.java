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

    /**
     * 以下 URL 常带签名/时效参数，禁止进入 toString。
     * <p>解析优先级固定为：下载用 {@code downloadUrl} → {@code ossUrl} → {@code domainUrl}；
     * 预览用 {@code previewUrl} → {@code domainUrl} → {@code downloadUrl} → {@code ossUrl}。
     * 四个字段都可能为 {@code null}，且链接有效期由生产者决定，本对象不会刷新。</p>
     */
    @ToString.Exclude
    private String downloadUrl;

    @ToString.Exclude
    private String previewUrl;

    /** 原始 OSS 地址，是 {@code downloadUrl} 的来源别名；缺少 {@code downloadUrl} 时按上述顺序回落。 */
    @ToString.Exclude
    private String ossUrl;

    /** 原始域名地址，是 {@code previewUrl} 的来源别名；缺少 {@code previewUrl} 时按上述顺序回落。 */
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
