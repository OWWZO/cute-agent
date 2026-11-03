package org.wwz.ai.domain.agent.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码解释器工具请求模型，承载任务、文件和流式执行参数。
 * <p>字段与远端 {@code cute-tool} 的 {@code CIRequest} 对齐；未使用的可选字段不再保留，
 * 避免出现永远为 null 的“死载荷”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeInterpreterRequest {
    private String requestId;
    private String query;
    private String task;
    private List<String> fileNames;
    private String fileName;
    private Boolean stream;
}
