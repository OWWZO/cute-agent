package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生图工作台统一文件视图。
 * <p>URL 字段语义：{@code ossUrl} 是对象存储原始引用，{@code domainUrl} 是绑定域名地址，
 * {@code downloadUrl} 是下载地址，{@code previewUrl} 是页面展示地址。生产方至少应填写一个
 * 可下载 URL（优先 {@code downloadUrl}，其次 {@code ossUrl}/{@code domainUrl}）。</p>
 * <p>消费方取值优先级：展示用 {@code previewUrl → domainUrl → downloadUrl → ossUrl}；
 * 下载/存储键用 {@code downloadUrl → ossUrl → previewUrl → domainUrl → fileName}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageFile {
    private String fileName;
    private String ossUrl;
    private String domainUrl;
    private String downloadUrl;
    private String previewUrl;
    private Long fileSize;
    private String mimeType;
}
