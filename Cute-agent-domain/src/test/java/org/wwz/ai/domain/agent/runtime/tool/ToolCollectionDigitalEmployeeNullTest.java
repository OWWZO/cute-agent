package org.wwz.ai.domain.agent.runtime.tool;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * 没有 agentContext 的后台/测试路径更新数字员工配置时不能 NPE；
 * 空配置也不能把已生效映射清空。
 */
public class ToolCollectionDigitalEmployeeNullTest {

    @Test
    public void nullConfigWithoutContextShouldNotThrowOrWipeExistingMapping() {
        ToolCollection collection = new ToolCollection();

        collection.updateDigitalEmployee(JSONObject.parseObject("{\"tool-a\":\"助手\"}"));
        Assert.assertEquals("助手", collection.getDigitalEmployee("tool-a"));

        collection.updateDigitalEmployee(null);

        Assert.assertEquals("助手", collection.getDigitalEmployee("tool-a"));
    }
}
