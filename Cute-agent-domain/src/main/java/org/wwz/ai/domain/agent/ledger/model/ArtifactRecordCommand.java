package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 写入输入/输出产物的命令对象。
 * <p>字段语义与实体 {@link org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord} 对齐；
 * {@code artifactRole} / {@code visibility} / {@code sourceType} 的取值分别来自
 * {@code ExecutionLedgerConstants} 的 {@code ARTIFACT_ROLE_*} / {@code VISIBILITY_*} /
 * {@code SOURCE_TYPE_*} 常量。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactRecordCommand {

    /** 所属 run */
    private Long runId;

    /** 非 run 场景下用于直连请求 */
    private String requestId;

    /** 输出文件对应的 tool invocation，输入文件为空 */
    private Long toolInvocationId;

    /** 输出文件对应的 toolCallId，输入文件为空 */
    private String toolCallId;

    /** {@code input} / {@code output} */
    private String artifactRole;

    /** {@code visible} / {@code internal} */
    private String visibility;

    /** {@code user_upload} / {@code tool_output} */
    private String sourceType;

    /** 来源名称 */
    private String sourceName;

    /** 文件名；为空的记录在写入时被跳过 */
    private String fileName;

    /** 稳定资源 key；{@code null} 落库为空串 */
    private String storageKey;

    /** 下载地址 */
    private String downloadUrl;

    /** 预览地址 */
    private String previewUrl;

    /** MIME 类型 */
    private String mimeType;

    /** 文件大小 */
    private Long fileSize;

    /** 文件哈希 */
    private String fileHash;

    /**
     * 扩展元数据 JSON。回放按其中的 {@code relativePath}（其次 {@code originFileName}）
     * 还原工作区相对路径，见 {@link org.wwz.ai.domain.agent.ledger.replay.ArtifactRelativePath}。
     */
    private String metadataJson;
}
