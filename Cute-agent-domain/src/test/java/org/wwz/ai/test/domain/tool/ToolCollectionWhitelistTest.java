package org.wwz.ai.test.domain.tool;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.Map;
import java.util.Set;

/**
 * 工具派发白名单必须按「本次调用的有效上下文」判定。
 * <p>回归攻击路径：实例字段 agentContext 为空、调用方传入受限子上下文时，
 * 旧实现会直接跳过白名单。</p>
 */
public class ToolCollectionWhitelistTest {

    @Test
    public void explicitRestrictedContextIsEnforcedWithoutInstanceContext() {
        ToolCollection tools = new ToolCollection();
        tools.addTool(echoTool("workspace_read"));

        AgentContext child = AgentContext.builder()
                .requestId("child")
                .toolDispatchWhitelist(Set.of("bash"))
                .build();

        Object result = tools.executeResolved("workspace_read", Map.of(), child);

        Assert.assertTrue("受限子上下文必须拦截非白名单工具，实际返回: " + result,
                String.valueOf(result).contains("denied non-whitelisted tool"));
    }

    @Test
    public void whitelistedToolStillRunsForExplicitContext() {
        ToolCollection tools = new ToolCollection();
        tools.addTool(echoTool("workspace_read"));

        AgentContext child = AgentContext.builder()
                .requestId("child")
                .toolDispatchWhitelist(Set.of("workspace_read"))
                .build();

        Assert.assertEquals("executed", tools.executeResolved("workspace_read", Map.of(), child));
    }

    private static BaseTool echoTool(String name) {
        return new BaseTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of();
            }

            @Override
            public Object execute(Object input, AgentContext context) {
                return "executed";
            }
        };
    }
}
