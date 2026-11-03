package org.wwz.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话附件上传结果（领域值对象，不依赖 infrastructure DTO）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationUploadedFile {
    /** 文件名；当前适配器填客户端原始文件名，与 {@link #originFileName} 同值。 */
    private String name;
    /** 访问地址（预览优先，取文件服务 domainUrl，其次 downloadUrl）。 */
    private String url;
    /** 文件扩展名（小写、不含点），不是 MIME 类型；无法解析时为空字符串。 */
    private String type;
    /** 文件字节数；优先取文件服务返回值，缺失时用上传请求的大小。 */
    private Long size;
    /** 下载地址（文件服务 downloadUrl 优先，其次 domainUrl）。 */
    private String downloadUrl;
    /** 预览地址（文件服务 domainUrl 优先，其次 downloadUrl）。 */
    private String previewUrl;
    /** 会话内稳定资源键：{@code sessionId:fileName:sha256或size}，用于去重与回放。 */
    private String resourceKey;
    /** 上传请求声明的 MIME 类型，可能为 {@code null}。 */
    private String mimeType;
    /** 客户端上传时的原始文件名。 */
    private String originFileName;
}
