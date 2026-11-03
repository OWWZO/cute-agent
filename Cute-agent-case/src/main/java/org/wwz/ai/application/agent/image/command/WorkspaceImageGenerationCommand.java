package org.wwz.ai.application.agent.image.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * 工作台生图用例输入。
 * <p>由 Trigger 从 HTTP 请求 VO 映射而来，隔离 HTTP 契约与领域命令。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationCommand {

    private String requestId;
    @ToString.Exclude
    private String prompt;
    private String mode;

    /** 编辑操作的源图片列表；非空时运行时会推导为 {@code edits} 模式。 */
    @ToString.Exclude
    private List<String> fileNames;
    /** 与 {@link #fileNames} 顺序对应的可选蒙版图片列表。 */
    @ToString.Exclude
    private List<String> maskFileNames;
    /** 生成结果的输出文件名，不是源图片；源图片由 {@link #fileNames} 传入。 */
    @ToString.Exclude
    private String fileName;
    @ToString.Exclude
    private String fileDescription;
    private String size;
    private Integer n;
}
