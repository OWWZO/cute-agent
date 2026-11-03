package org.wwz.ai.test.domain.ltm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;
import org.wwz.ai.domain.agent.memory.ltm.MemoryProvider;

import java.util.List;
import java.util.Map;

/**
 * 工具调用失败时返回给模型的 JSON 必须始终可解析。
 */
public class LtmManagerToolCallJsonTest {

    @Test
    public void providerExceptionMessageIsJsonEscaped() {
        LtmManager manager = new LtmManager();
        manager.addProvider(new ThrowingProvider("bad \"quote\"\nand\\slash"));

        String payload = manager.handleToolCall("memory_tool", Map.of());

        JSONObject parsed = JSON.parseObject(payload);
        Assert.assertEquals(Boolean.FALSE, parsed.getBoolean("success"));
        Assert.assertEquals("bad \"quote\"\nand\\slash", parsed.getString("message"));
    }

    @Test
    public void providerDefaultsToNonExternal() {
        MemoryProvider provider = new ThrowingProvider("x");
        Assert.assertFalse(provider.isExternal());
    }

    private static final class ThrowingProvider implements MemoryProvider {

        private final String message;

        private ThrowingProvider(String message) {
            this.message = message;
        }

        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public List<Map<String, Object>> getToolSchemas() {
            return List.of(Map.of("name", "memory_tool"));
        }

        @Override
        public String handleToolCall(String toolName, Map<String, Object> args) {
            throw new IllegalStateException(message);
        }
    }
}
