package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下游图片生成服务返回的单张图片文件信息。
 * <p>各 URL 字段允许为空，由内核在归一化为工作台文件模型时按优先级回退；
 * 消费方不应假设所有 URL 同时存在。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationGatewayFile {
    /** 文件服务返回的文件名（含扩展名）。 */
    private String fileName;
    /** 稳定对象存储引用；当前网关优先用文件服务的下载地址填充。 */
    private String ossUrl;
    /** 域名访问地址（预览用）；当前网关优先用文件服务的预览地址填充。 */
    private String domainUrl;
    /** 下载地址；为空时内核按 ossUrl -> domainUrl 回退。 */
    private String downloadUrl;
    /** 预览地址；为空时内核按 domainUrl -> downloadUrl -> ossUrl 回退。 */
    private String previewUrl;
    /** 文件字节数；上游未返回时为 {@code null}。 */
    private Long fileSize;
    /** MIME 类型；上游未返回时由网关按图片内容推断。 */
    private String mimeType;
}
