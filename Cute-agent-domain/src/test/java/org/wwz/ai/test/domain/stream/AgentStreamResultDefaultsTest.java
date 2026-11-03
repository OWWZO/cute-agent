package org.wwz.ai.test.domain.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

/**
 * 实时链路直接 new 的帧必须与 builder 路径拥有相同默认值。
 */
public class AgentStreamResultDefaultsTest {

    @Test
    public void noArgConstructorAppliesBuilderDefaults() {
        AgentStreamResult result = new AgentStreamResult();

        Assert.assertEquals("running", result.getStatus());
        Assert.assertEquals("", result.getContentDelta());
        Assert.assertEquals("", result.getContent());
        Assert.assertEquals("markdown", result.getContentType());
        Assert.assertEquals("result", result.getFrameType());
    }
}
