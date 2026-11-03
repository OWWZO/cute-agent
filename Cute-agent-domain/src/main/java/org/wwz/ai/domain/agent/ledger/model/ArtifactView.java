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
