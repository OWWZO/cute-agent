package org.wwz.ai.test.domain.planmode;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.SendMessageTool;

import java.util.Map;

/**
 * SendMessage 投递与子 Agent 收尾并发时，不能让消息静默丢失却返回成功。
 */
public class SendMessageToolTest {

    private final SendMessageTool tool = new SendMessageTool();

    @After
    public void tearDown() {
        SessionAgentMailboxHub.clearAll();
    }

    @Test
    public void closedMailboxFallsBackToResumeInsteadOfReportingDelivered() {
        AgentContext context = contextWithRunningTask("sess-1", "agent-1");
        // 已物化但未激活的邮箱：closed=false，offer 会“成功”入队，但没有人消费。
        SessionAgentMailboxHub.queue("sess-1", "agent-1");

        Object result = tool.execute(Map.of("to", "agent-1", "message", "改查登录模块"), context);

        Map<?, ?> data = (Map<?, ?>) ((ToolResultPayload) result).getLlmData();
        Assert.assertEquals(Boolean.FALSE, data.get("ok"));
        Assert.assertTrue(String.valueOf(data.get("message")).contains("SubAgentRunner 未注入"));
        Assert.assertTrue("投递失败的消息必须撤回，避免下次唤醒重复投递",
                SessionAgentMailboxHub.queue("sess-1", "agent-1").isEmpty());
    }

    @Test
    public void activeMailboxStillReportsQueued() {
        AgentContext context = AgentContext.builder()
                .requestId("req-2")
                .sessionId("sess-2")
                .build();
        SessionAgentMailboxHub.markActive("sess-2", "agent-2", true);

        Object result = tool.execute(Map.of("to", "agent-2", "message", "继续"), context);

        ToolResultPayload payload = (ToolResultPayload) result;
        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        Map<?, ?> data = (Map<?, ?>) payload.getLlmData();
        Assert.assertEquals(Boolean.TRUE, data.get("ok"));
        Assert.assertEquals(1, data.get("queued"));
        Assert.assertEquals(1, SessionAgentMailboxHub.queue("sess-2", "agent-2").size());
    }

    private static AgentContext contextWithRunningTask(String sessionId, String agentId) {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry(sessionId, null);
        RuntimeBackgroundTask task = registry.registerLocalAgent("inspect", "general-purpose", "prompt");
        registry.bindAgentId(task.getId(), agentId);
        return AgentContext.builder()
                .requestId("req-1")
                .sessionId(sessionId)
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();
    }
}
