package org.wwz.ai.application.agent.image.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台生图历史批次用例输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageHistoryBatchView {

    private String requestId;
    private String prompt;
    private String mode;
    private String size;
    private Integer batchCount;
    private Integer sourceImageCount;
    private Integer maskImageCount;
    private Boolean usedFallback;
    private LocalDateTime createdAt;

    /**
     * 该批次包含的生成文件；可能为 null，调用方应按空集合处理。
     * <p>该字段不承诺返回只读或防御性复制的集合，调用方不得在读取后修改其内容。</p>
     */
    private List<WorkspaceImageFileView> images;
}
