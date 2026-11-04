package org.wwz.ai.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.runtime.llm.LlmToolDefinition;
import org.wwz.ai.infrastructure.llm.springai.SpringAiToolCallbackProvider;

import java.util.List;
import java.util.Map;

public class SpringAiToolCallbackProviderTest {

    @Test
    public void boundsSignatureCacheForOneLongLivedSession() {
        SpringAiToolCallbackProvider provider = new SpringAiToolCallbackProvider();
        for (int i = 0; i < 40; i++) {
            provider.buildToolCallbacks(List.of(LlmToolDefinition.builder()
                    .name("tool-" + i)
                    .description("tool")
                    .inputSchema("{}")
                    .build()), "session-1");
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, List<ToolCallback>>> sessionCache =
                (Map<String, Map<String, List<ToolCallback>>>) ReflectionTestUtils.getField(provider, "sessionCache");
        Assert.assertNotNull(sessionCache);
        Assert.assertEquals(1, sessionCache.size());
        Assert.assertTrue(sessionCache.get("session-1").size() <= 32);
    }
}
