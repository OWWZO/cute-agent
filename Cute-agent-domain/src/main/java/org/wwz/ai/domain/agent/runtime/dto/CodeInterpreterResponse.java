package org.wwz.ai.domain.agent.runtime.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码解释器工具响应模型，承载执行结果、输出文件和错误信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeInterpreterResponse {
    private String requestsId;
    private String resultType;
    /** 终态事件携带的最终答复文本；用于结论兜底与 payload 的 content 字段，不参与过程累加。 */
    private String content;
    /** 当前事件对应的代码片段；过程事件原样透传给 UI，不进入累计正文。 */
    private String code;
    /** 执行输出/终态结论；final 事件中优先作为结论，并写入 payload 的 codeOutput。 */
    private String codeOutput;
    private List<FileInfo> fileInfo;
    private String explain;
    private Integer step;
    /** 上游过程事件的 Markdown 增量；本端逐事件累加，final 时用累计全文覆盖后再下发。 */
    private String data;
    private Boolean isFinal;
    private String toolCallId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileInfo {
        private String fileName;
        private String ossUrl;
        private String domainUrl;
        private Integer fileSize;
        private String relativePath;
    }
}
