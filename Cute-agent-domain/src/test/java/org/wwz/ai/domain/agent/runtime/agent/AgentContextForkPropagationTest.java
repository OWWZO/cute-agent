package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

/**
 * fork 出来的并行子任务必须继承父上下文的工具池与模型开关。
 */
public class AgentContextForkPropagationTest {

    @Test
    public void forkShouldCarryToolCollectionAndModelSettings() {
        ToolCollection tools = new ToolCollection();
        AgentContext parent = AgentContext.builder()
                .requestId("req-fork")
                .sessionId("session-fork")
                .query("父任务")
                .toolCollection(tools)
                .model("claude-sonnet")
                .thinking(Boolean.TRUE)
                .thinkingEffort("high")
                .build();

        AgentContext fork = parent.forkForParallelTask("并发子任务");

        Assert.assertSame(tools, fork.getToolCollection());
        Assert.assertEquals("claude-sonnet", fork.getModel());
        Assert.assertEquals(Boolean.TRUE, fork.getThinking());
        Assert.assertEquals("high", fork.getThinkingEffort());
    }
}
