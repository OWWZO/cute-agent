package org.wwz.ai.test.domain.subagent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 后台 run 占位回归：check-then-act 之间必须原子 claim，否则并发 SendMessage 会开出两条 run。
 */
public class BackgroundSubAgentClaimTest {

    @After
    public void cleanup() {
        SessionAgentMailboxHub.clearAll();
    }

    @Test
    public void concurrentResumeSubmitsOnlyOneRun() throws Exception {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        AgentContext parent = AgentContext.builder()
                .requestId("req-claim")
                .sessionId("sess-claim")
                .sessionControl(new SessionControl().withBackgroundTasks(registry))
                .build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SubAgentRunner runner = Mockito.mock(SubAgentRunner.class);
        Mockito.when(runner.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return SubAgentResult.builder()
                            .status(SubAgentResult.STATUS_COMPLETED)
                            .agentId("agent-claim")
                            .agentType("general-purpose")
                            .content("done")
                            .build();
                });

        ToolResultPayload first = submit(parent, runner, "agent-claim");
        Assert.assertEquals(Boolean.TRUE, data(first).get("ok"));
        Assert.assertTrue(started.await(5, TimeUnit.SECONDS));

        ToolResultPayload second = submit(parent, runner, "agent-claim");
        Assert.assertEquals("第二个并发续跑不得再启动一条 run",
                Boolean.FALSE, data(second).get("ok"));
        Assert.assertTrue(String.valueOf(data(second).get("message")).contains("正在运行"));

        release.countDown();
        waitUntilIdle(registry);
        Assert.assertTrue("前一次 run 收尾后必须释放占位",
                Boolean.TRUE.equals(data(submit(parent, runner, "agent-claim")).get("ok")));
    }

    private static ToolResultPayload submit(AgentContext parent, SubAgentRunner runner, String agentId) {
        return BackgroundSubAgentExecutor.submit(parent, runner, new ActiveAgentRunRegistry(),
                "desc", "prompt", "general-purpose", agentId,
                "call-" + agentId, AgentDispatchTool.NAME);
    }

    private static Map<?, ?> data(ToolResultPayload payload) {
        Assert.assertTrue(payload.getLlmData() instanceof Map);
        return (Map<?, ?>) payload.getLlmData();
    }

    private static void waitUntilIdle(RuntimeBackgroundTaskRegistry registry) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!registry.listRunning().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        Thread.sleep(100L);
    }
}
