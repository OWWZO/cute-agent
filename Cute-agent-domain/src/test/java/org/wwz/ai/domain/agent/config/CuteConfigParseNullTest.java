package org.wwz.ai.domain.agent.config;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;

/**
 * 配置值是 JSON 字面量 "null" 时，解析结果必须归一为空 Map，否则消费方 getOrDefault 会 NPE。
 */
public class CuteConfigParseNullTest {

    @Test
    public void jsonNullShouldProduceEmptyMaps() {
        CuteConfig config = new CuteConfig();

        config.setMultiAgentToolList("null");
        config.setSensitivePatterns("null");
        config.setMessageInterval("null");

        Assert.assertNotNull(config.getMultiAgentToolListMap());
        Assert.assertTrue(config.getMultiAgentToolListMap().isEmpty());
        Assert.assertNotNull(config.getSensitivePatterns());
        Assert.assertTrue(config.getSensitivePatterns().isEmpty());
        Assert.assertNotNull(config.getMessageInterval());
        Assert.assertTrue(config.getMessageInterval().isEmpty());
        Assert.assertEquals("default", config.getMessageInterval().getOrDefault("k", "default"));
    }
}
