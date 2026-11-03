package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生图工作台生成结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationResult {
    private String data;
    /** 生成的图片文件列表。与 {@code ImageGenerationExecutionResult#files} 同名对齐。 */
    private List<WorkspaceImageFile> files;
    private String requestId;
    private String mode;
    private Boolean usedFallback;
    private Object rawResponse;
}
