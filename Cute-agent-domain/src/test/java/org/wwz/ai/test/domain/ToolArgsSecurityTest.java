package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;

import java.util.Map;

/**
 * 模型可控的工具入参不得触发 fastjson autoType 任意类实例化。
 */
public class ToolArgsSecurityTest {

    @Test
    public void rejectsAutoTypePayloadFromModelArgs() {
        Map<String, Object> parsed = ToolArgs.map(
                "{\"@type\":\"java.lang.Thread\",\"name\":\"evil\"}");

        Assert.assertTrue("safeMode 必须拒绝 @type 自动实例化", parsed.isEmpty());
    }

    @Test
    public void stillParsesPlainJsonArguments() {
        Map<String, Object> parsed = ToolArgs.map("{\"path\":\"a.txt\",\"line\":3}");

        Assert.assertEquals("a.txt", parsed.get("path"));
        Assert.assertEquals(3, parsed.get("line"));
    }
}
