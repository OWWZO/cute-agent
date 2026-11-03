package org.wwz.ai.application.agent.image.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作台生图文件用例输出。
 * <p>生产方正常应至少提供一个可下载 URL，但四个 URL 字段都按可空处理。URL 是否临时有效
 * 由上游文件服务决定，本视图不承诺永久可访问；链接失效时应重新查询历史记录获取新地址。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageFileView {

    /** 生成文件的逻辑名称，仅用于展示和下载命名，不应作为本地文件系统路径直接使用。 */
    private String fileName;

    /**
     * 对象存储原始引用，可能暴露内部 bucket/endpoint 命名，不应直接展示给最终用户。
     * 该字段可为 null，客户端下载应优先使用 {@link #downloadUrl}。
     */
    private String ossUrl;

    /** 文件服务绑定域名地址，适合对外访问；可为 null，展示时优先使用 {@link #previewUrl}。 */
    private String domainUrl;

    /** 下载地址；可为 null。下载或保存时优先使用该字段，缺失时再回退到其他 URL。 */
    private String downloadUrl;

    /** 页面预览地址；可为 null。展示时优先使用该字段，缺失时再回退到其他 URL。 */
    private String previewUrl;

    /** 文件大小，单位字节；null 表示上游未记录，格式化或求和前必须判空。 */
    private Long fileSize;

    /** MIME 类型，例如 {@code image/png}；可能为 null。 */
    private String mimeType;
}
