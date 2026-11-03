package org.wwz.ai.domain.agent.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 文件工具请求模型，承载文件操作参数和工作区上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRequest {
    private String requestId;
    private String fileName;
    private String description;
    /** 文本内容；upload 操作必填。可能含用户数据，禁止进入 toString。 */
    @ToString.Exclude
    private String content;
    /** 本地已落盘绝对路径；register/materialize 操作必填，upload 可忽略。会泄漏服务器目录结构，禁止进入 toString。 */
    @ToString.Exclude
    private String localPath;
}
