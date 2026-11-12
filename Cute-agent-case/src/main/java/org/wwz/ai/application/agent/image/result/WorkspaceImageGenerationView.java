package org.wwz.ai.application.agent.image.result;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * 工作台生图生成用例输出。
 * <p>该对象会直接作为应用层结果返回；Lombok {@code @Data} 会生成 {@code toString()}，
 * 其中 {@code rawResponse} 已排除，避免把可能很大的上游内部信息写入日志。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationView {

    /** 生成结果摘要文本，不是图片二进制、base64 或图片 URL；图片本体见 {@link #fileInfo}。 */
    private String data;

    /** 生成文件列表；正常映射路径返回空列表而非 null，元素字段仍可能为 null。 */
    private List<WorkspaceImageFileView> fileInfo;

    /** 本次生成请求的关联 ID；优先使用调用方传入值，缺省时由服务生成。 */
    private String requestId;

    /** 生图模式，当前取值为 {@code images} 或 {@code edits}。 */
    private String mode;

    /** 是否使用了兼容接口回退；null 表示上游未明确返回该标志，直接拆箱前必须判空。 */
    private Boolean usedFallback;

    /**
     * 上游原始响应的诊断快照，可能包含模型、尺寸、备注和较大的内部数据。
     * <p>不属于稳定的终端展示契约，不应直接暴露给最终用户，也不应写入普通日志。</p>
     */
    @ToString.Exclude
    private JSONObject rawResponse;
}
