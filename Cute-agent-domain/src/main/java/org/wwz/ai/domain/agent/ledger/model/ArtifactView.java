package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 产物查询视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactView {

    private Long id;

    private Long runId;

    private String requestId;

    private Long toolInvocationId;

    private String toolCallId;

    /** input / output。 */
    private String artifactRole;

    /** 取值见 {@code ExecutionLedgerConstants.VISIBILITY_VISIBLE} / {@code VISIBILITY_INTERNAL}。 */
    private String visibility;

    /** user_upload / tool_output。 */
    private String sourceType;

    private String sourceName;

    private String fileName;

    /**
     * 不透明的资源标识，不是服务器存储路径/前缀。
     * <p>生产方必须保证写入的是 resourceKey、对象存储 URL、文件服务 URL 或展示文件名，
     * 不得写入本地绝对路径、bucket/目录布局等内部信息；客户端只应把它当作不可解析的 key。</p>
     */
    private String storageKey;

    private String downloadUrl;

    private String previewUrl;

    private String mimeType;

    private Long fileSize;

    private String fileHash;

    /**
     * 扩展元数据 JSON，原样透传、不保证可解析。
     * <p>可能为 {@code null} 或非法 JSON；消费方必须自行容错（按缺省值处理），
     * 不得假设该字段一定包含可解析的 metadata。</p>
     */
    private String metadataJson;

    private LocalDateTime createTime;
}
