package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生图历史批次视图。
 * <p>{@code batchCount}/{@code sourceImageCount}/{@code maskImageCount}/{@code usedFallback}
 * 在上游输出缺省时可能为 {@code null}，消费方必须做空值处理，不得直接拆箱。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationHistoryBatch {
    private String requestId;
    private String prompt;
    private String mode;
    private String size;
    private Integer batchCount;
    private Integer sourceImageCount;
    private Integer maskImageCount;
    private Boolean usedFallback;
    private LocalDateTime createdAt;
    private List<WorkspaceImageFile> images;
}
