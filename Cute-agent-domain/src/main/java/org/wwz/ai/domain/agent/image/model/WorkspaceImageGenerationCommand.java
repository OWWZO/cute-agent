package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生图工作台生成命令。
 * <p>{@link #fileNames} / {@link #maskFileNames} 按引用持有，不做防御性拷贝；
 * 命令交给异步执行路径后，调用方不得再修改传入的列表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationCommand {
    private String requestId;
    private String prompt;
    private String mode;
    private List<String> fileNames;
    private List<String> maskFileNames;
    private String fileName;
    private String fileDescription;
    private String model;
    private String size;
    private Integer n;
}
