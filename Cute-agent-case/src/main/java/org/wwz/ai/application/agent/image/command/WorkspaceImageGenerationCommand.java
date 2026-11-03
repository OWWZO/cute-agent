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
    @ToString.Exclude
    private List<String> fileNames;
    @ToString.Exclude
    private List<String> maskFileNames;
    @ToString.Exclude
    private String fileName;
    @ToString.Exclude
    private String fileDescription;
    private String size;
    private Integer n;
}
