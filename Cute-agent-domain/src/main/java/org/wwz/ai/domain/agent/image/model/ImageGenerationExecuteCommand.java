package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一的生图执行命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecuteCommand {

    private String requestId;

    private String prompt;

    private String mode;

    /** 输入图片文件名列表（图生图的参考图），不是产出物名。 */
    private List<String> fileNames;

    /** 输入 mask 图片文件名列表，与 {@link #fileNames} 一一对应。 */
    private List<String> maskFileNames;

    /** 产出物文件名（单数）；与上面的输入列表语义不同。 */
    private String fileName;

    private String fileDescription;

    private String model;

    private String size;

    private Integer n;

    private Integer timeoutSeconds;
}
