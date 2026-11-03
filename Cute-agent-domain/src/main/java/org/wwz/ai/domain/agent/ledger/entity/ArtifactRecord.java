package org.wwz.ai.domain.agent.ledger.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 输入/输出文件归属账本。
 * <p>归属不变量：{@code runId} 与 {@code requestId} 至少有一个非空；
 * {@code artifactRole=output} 时 {@code toolInvocationId} / {@code toolCallId} 应指向产生该产物的工具调用。
 * 两者都为空的行无法被任何按 run/session 的查询命中，属于孤儿行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ArtifactRecord {

    private Long id;

    /** 所属 run */
    private Long runId;

    /** 非 run 场景下用于直连请求 */
    private String requestId;

    /** 输出文件对应的 tool invocation，输入文件为空 */
    private Long toolInvocationId;

    /** 输出文件对应的 toolCallId，输入文件为空 */
    private String toolCallId;

    /** input / output */
    private String artifactRole;

    /** visible / internal */
    private String visibility;

    /** user_upload / tool_output */
    private String sourceType;

    /** 来源名称 */
    private String sourceName;

    /** 文件名 */
    private String fileName;

    /** 稳定资源 key */
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

    /** 扩展元数据 */
    private String metadataJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
