package org.wwz.ai.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * image_generation_tool 输出表 PO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolOutputImageGenerationPO extends AbstractToolOutputPO {

    @ToString.Exclude
    private String prompt;

    private String mode;

    @ToString.Exclude
    private String summary;

    private String size;

    private Integer batchCount;

    private Integer sourceImageCount;

    private Integer maskImageCount;

    private Boolean usedFallback;
}
