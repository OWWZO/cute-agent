package org.wwz.ai.test.domain.image;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecuteCommand;
import org.wwz.ai.domain.agent.image.model.ImageGenerationGatewayRequest;
import org.wwz.ai.domain.agent.image.model.ImageGenerationGatewayResponse;
import org.wwz.ai.domain.agent.image.port.ImageGenerationGatewayPort;
import org.wwz.ai.domain.agent.image.service.impl.ImageGenerationExecutionKernelImpl;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 生图执行内核：调用方指定的 model 必须透传给网关，不能被静默丢弃。
 */
public class ImageGenerationExecutionKernelImplTest {

    @Test
    public void callerSpecifiedModelIsPropagatedToGatewayRequest() {
        AtomicReference<ImageGenerationGatewayRequest> captured = new AtomicReference<>();
        ImageGenerationGatewayPort gateway = request -> {
            captured.set(request);
            return ImageGenerationGatewayResponse.builder().data("ok").build();
        };

        new ImageGenerationExecutionKernelImpl(gateway).execute(ImageGenerationExecuteCommand.builder()
                .requestId("req-img-1")
                .prompt("画一只猫")
                .model("gpt-image-1")
                .build());

        Assert.assertNotNull(captured.get());
        Assert.assertEquals("gpt-image-1", captured.get().getModel());
    }

    @Test
    public void blankModelFallsBackToGatewayDefault() {
        AtomicReference<ImageGenerationGatewayRequest> captured = new AtomicReference<>();
        ImageGenerationGatewayPort gateway = request -> {
            captured.set(request);
            return ImageGenerationGatewayResponse.builder().data("ok").build();
        };

        new ImageGenerationExecutionKernelImpl(gateway).execute(ImageGenerationExecuteCommand.builder()
                .requestId("req-img-2")
                .prompt("画一只猫")
                .model("  ")
                .build());

        Assert.assertNull(captured.get().getModel());
    }
}
