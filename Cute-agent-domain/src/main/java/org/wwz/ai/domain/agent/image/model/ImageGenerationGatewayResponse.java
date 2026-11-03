package org.wwz.ai.domain.agent.image.model;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下游图片生成服务响应模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationGatewayResponse {
    private String data;
    private List<ImageGenerationGatewayFile> fileInfo;
    private String requestId;
    private String mode;
    private Boolean usedFallback;
    /** 下游原始响应摘要，固定为 JSON 对象，便于直接序列化与检视。 */
    private JSONObject rawResponse;
}
