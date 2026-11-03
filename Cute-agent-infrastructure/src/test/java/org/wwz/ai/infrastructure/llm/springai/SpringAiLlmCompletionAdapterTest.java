package org.wwz.ai.infrastructure.llm.springai;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;

import java.lang.reflect.Method;
import java.util.Map;

public class SpringAiLlmCompletionAdapterTest {

    @Test
    public void cacheKeyShouldIgnorePerRequestExtParamsAndMaskApiKey() throws Exception {
        SpringAiLlmCompletionAdapter adapter = new SpringAiLlmCompletionAdapter(
                null, null, null, new CuteConfig());
        Method buildCacheKey = SpringAiLlmCompletionAdapter.class
                .getDeclaredMethod("buildCacheKey", LLMSettings.class);
        buildCacheKey.setAccessible(true);

        LLMSettings first = LLMSettings.builder()
                .model("model-a")
                .baseUrl(" http://llm.local ")
                .interfaceUrl(" /v1/custom ")
                .apiKey("secret-key")
                .extParams(Map.of("seed", 1))
                .build();
        LLMSettings second = LLMSettings.builder()
                .model("model-a")
                .baseUrl("http://llm.local")
                .interfaceUrl("/v1/custom")
                .apiKey("secret-key")
                .extParams(Map.of("seed", 2))
                .build();

        String firstKey = (String) buildCacheKey.invoke(adapter, first);
        String secondKey = (String) buildCacheKey.invoke(adapter, second);

        Assert.assertEquals(firstKey, secondKey);
        Assert.assertFalse(firstKey.contains("secret-key"));
    }
}
