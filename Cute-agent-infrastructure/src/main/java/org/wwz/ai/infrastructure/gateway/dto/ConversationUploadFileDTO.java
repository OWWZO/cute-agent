package org.wwz.ai.infrastructure.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端对话附件上传后的稳定文件信息。
 * <p>不使用 Lombok {@code @Builder}：避免运行时缺失 {@code $Builder} 内部类导致上传失败。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationUploadFileDTO {
    /** 上传文件展示名。 */
    private String name;

    /** 通用访问 URL；当前上传网关通常填入预览地址。 */
    private String url;

    /** 文件扩展名，不含点号。 */
    private String type;

    /** 文件大小，单位字节。 */
    private Long size;

    /** 下载地址。 */
    private String downloadUrl;

    /** 预览地址。 */
    private String previewUrl;

    /** 基于会话、文件名和内容特征生成的稳定去重键。 */
    private String resourceKey;

    /** HTTP content type。 */
    private String mimeType;

    /** 上传前的原始文件名。 */
    private String originFileName;

    public static ConversationUploadFileDTO of(String name,
                                               String url,
                                               String type,
                                               Long size,
                                               String downloadUrl,
                                               String previewUrl,
                                               String resourceKey,
                                               String mimeType,
                                               String originFileName) {
        ConversationUploadFileDTO dto = new ConversationUploadFileDTO();
        dto.setName(name);
        dto.setUrl(url);
        dto.setType(type);
        dto.setSize(size);
        dto.setDownloadUrl(downloadUrl);
        dto.setPreviewUrl(previewUrl);
        dto.setResourceKey(resourceKey);
        dto.setMimeType(mimeType);
        dto.setOriginFileName(originFileName);
        return dto;
    }
}
