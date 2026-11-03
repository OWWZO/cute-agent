package org.wwz.ai.domain.agent.image.port;

import org.wwz.ai.domain.agent.image.model.ImageGenerationGatewayRequest;
import org.wwz.ai.domain.agent.image.model.ImageGenerationGatewayResponse;

/**
 * 生图工作台下游调用端口。
 */
public interface ImageGenerationGatewayPort {

    /**
     * 调用下游图片生成服务。
     * <p>同步阻塞直到下游返回或超时（超时上限由请求的 {@code timeoutSeconds} 决定）。
     * 端口不承诺幂等：重复调用同一 {@code requestId} 可能触发重复生成。</p>
     */
    ImageGenerationGatewayResponse generate(ImageGenerationGatewayRequest request);
}
