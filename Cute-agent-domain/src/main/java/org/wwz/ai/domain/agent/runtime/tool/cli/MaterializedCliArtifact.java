package org.wwz.ai.domain.agent.runtime.tool.cli;

import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI 产物对外暴露的安全视图。
 * <p>
 * 只描述 workspace 内的相对路径和文件服务生成的地址，绝不包含服务器绝对路径，
 * 也不包含 relay / CDP / Cookie 等连接信息。
 * <p>安全保证由构造期校验兜住：{@code relativePath} 必须是可证明落在工作区内的相对路径，
 * 越界值直接拒绝（{@link IllegalArgumentException}），不允许先构造再在消费端补救。
 */
@Value
public class MaterializedCliArtifact {

    /** workspace 内相对路径，已通过根目录校验和 {@code ..} 拒绝。 */
    String relativePath;

    /** 展示文件名。 */
    String fileName;

    /** 文件字节数。 */
    Long fileSize;

    /** 可选 MIME 类型，探测失败时为空。 */
    String mimeType;

    /** 文件服务预览地址，可为空。 */
    String previewUrl;

    /** 文件服务下载地址，登记成功时必然存在。 */
    String downloadUrl;

    @Builder
    public MaterializedCliArtifact(String relativePath,
                                   String fileName,
                                   Long fileSize,
                                   String mimeType,
                                   String previewUrl,
                                   String downloadUrl) {
        if (StringUtils.isBlank(relativePath) || !WorkspaceRelativePath.isSafe(relativePath)) {
            throw new IllegalArgumentException("relativePath must stay inside workspace");
        }
        this.relativePath = WorkspaceRelativePath.sanitize(relativePath);
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.previewUrl = previewUrl;
        this.downloadUrl = downloadUrl;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relativePath", relativePath);
        map.put("fileName", fileName);
        map.put("fileSize", fileSize);
        map.put("mimeType", mimeType);
        map.put("previewUrl", previewUrl);
        map.put("downloadUrl", downloadUrl);
        return map;
    }
}
