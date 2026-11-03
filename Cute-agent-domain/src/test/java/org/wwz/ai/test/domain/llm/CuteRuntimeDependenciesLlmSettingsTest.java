package org.wwz.ai.test.domain.llm;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * yml 中的 LLMSettings 是全局共享实例，每轮覆盖 thinking 不能污染它。
 */
public class CuteRuntimeDependenciesLlmSettingsTest {

    @Test
    public void ymlSettingsAreCopiedBeforePerRequestMutation() {
        CuteConfig config = new CuteConfig();
        Map<String, Object> extParams = new HashMap<>();
        extParams.put("keep", "yml");
        LLMSettings yml = LLMSettings.builder()
                .model("m")
                .reasoningEffort("low")
                .extParams(extParams)
                .build();
        ReflectionTestUtils.setField(config, "llmSettingsMap", Map.of("m", yml));
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .cuteConfig(config)
                .build();

        LLMSettings resolved = dependencies.resolveLlmSettings("m");
        Assert.assertNotSame(yml, resolved);

        resolved.setReasoningEffort("high");
        resolved.getExtParams().put("request", "only");

        Assert.assertEquals("low", yml.getReasoningEffort());
        Assert.assertFalse(yml.getExtParams().containsKey("request"));
    }
}
