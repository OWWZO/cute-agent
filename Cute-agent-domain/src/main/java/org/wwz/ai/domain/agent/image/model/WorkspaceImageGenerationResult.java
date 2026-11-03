package org.wwz.ai.domain.agent.image.model;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * 生图工作台生成结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationResult {
    /** 结果摘要文本，不是图片二进制或图片 URL；图片本体见 {@link #files}。 */
    private String data;
    /** 生成的图片文件列表。与 {@code ImageGenerationExecutionResult#files} 同名对齐。 */
    private List<WorkspaceImageFile> files;
    private String requestId;
    private String mode;
    private Boolean usedFallback;
    /**
     * 上游原始响应，可能非常大（甚至包含 base64）。排除出 toString/equals，
     * 避免日志打印和相等性判断遍历整包响应。
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private JSONObject rawResponse;
}
