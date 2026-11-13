package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.runtime.CuteLlmDependencies;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 子 Agent 邮箱只能由 runUnlocked 的外层 finally 释放一次。
 */
public class SubAgentRunnerMailboxReleaseTest {

    private static final String PARENT_SESSION = "sess-mailbox-release";

    @Test
    public void mailboxIsReleasedOnlyOnceAfterRunnerFlow() {
        String agentId = "agentmailboxonce";
        try (MockedStatic<SessionAgentMailboxHub> mailbox =
                     mockStatic(SessionAgentMailboxHub.class, CALLS_REAL_METHODS)) {
            runRunner(PARENT_SESSION, agentId);

            mailbox.verify(
                    () -> SessionAgentMailboxHub.markActive(PARENT_SESSION, agentId, false),
                    times(1));
        } finally {
            SessionAgentMailboxHub.clearAll();
        }
    }

    @Test
    public void messageOfferedBetweenReleaseWindowsSurvives() throws Exception {
        String agentId = "agentmailboxwindow";
        AtomicBoolean contenderInjected = new AtomicBoolean();
        AtomicReference<Throwable> contenderFailure = new AtomicReference<>();

        try (MockedStatic<SessionAgentMailboxHub> ignored =
                     mockStatic(SessionAgentMailboxHub.class, invocation -> {
                         Object result = invocation.callRealMethod();
                         if (isFirstMailboxRelease(invocation, agentId)
                                 && contenderInjected.compareAndSet(false, true)) {
                             injectBetweenReleaseWindows(agentId, contenderFailure);
                         }
                         return result;
                     })) {
            runRunner(PARENT_SESSION, agentId);

            Assert.assertTrue("并发窗口必须被真实触发", contenderInjected.get());
            Assert.assertNull("并发投递线程不应失败", contenderFailure.get());
            PendingInjectMessage message = SessionAgentMailboxHub
                    .queue(PARENT_SESSION, agentId)
                    .poll();
            Assert.assertNotNull("两次释放之间的 SendMessage 不能被第二次释放清掉", message);
            Assert.assertEquals("继续执行", message.getText());
        } finally {
            SessionAgentMailboxHub.clearAll();
        }
    }

    private static boolean isFirstMailboxRelease(InvocationOnMock invocation, String agentId) {
        return "markActive".equals(invocation.getMethod().getName())
                && PARENT_SESSION.equals(invocation.getArgument(0))
                && agentId.equals(invocation.getArgument(1))
                && Boolean.FALSE.equals(invocation.getArgument(2));
    }

    private static void injectBetweenReleaseWindows(String agentId,
                                                    AtomicReference<Throwable> failure)
            throws InterruptedException {
        Thread contender = new Thread(() -> {
            try {
                if (!SessionAgentMailboxHub.tryMarkActive(PARENT_SESSION, agentId)) {
                    throw new AssertionError("第一次释放后必须允许另一线程重新领取邮箱");
                }
                int queued = SessionAgentMailboxHub.offer(
                        PARENT_SESSION,
                        agentId,
                        PendingInjectMessage.builder()
                                .text("继续执行")
                                .source(PendingInjectMessage.SOURCE_COORDINATOR)
                                .build());
                if (queued != 1) {
                    throw new AssertionError("并发投递失败，queued=" + queued);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "mailbox-contender");
        contender.start();
        contender.join(5000L);
        if (contender.isAlive()) {
            contender.interrupt();
            failure.set(new AssertionError("并发投递线程未在 5 秒内完成"));
        }
    }

    private static void runRunner(String sessionId, String agentId) {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        when(recorder.createRun(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        runner.run(parentContext(recorder, sessionId), "验证邮箱释放", "prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, agentId);
    }

    private static AgentContext parentContext(AgentExecutionRecorder recorder, String sessionId) {
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "reactMaxSteps", 5);
        ReflectionTestUtils.setField(config, "maxObserve", "0");
        ReflectionTestUtils.setField(config, "digitalEmployeePrompt", "");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("llm.default.base_url", "https://test.local");
        CuteLlmDependencies llmDependencies = CuteLlmDependencies.builder()
                .completionPort(mock(LlmCompletionPort.class))
                .streamResponseHandler(new StreamResponseHandler())
                .build();
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .cuteConfig(config)
                .environment(environment)
                .llmDependencies(llmDependencies)
                .build();
        return AgentContext.builder()
                .requestId("req-mailbox-release")
                .sessionId(sessionId)
                .query("parent query")
                .model("test-model")
                .executionRecorder(recorder)
                .toolCollection(new ToolCollection())
                .runtimeDependencies(dependencies)
                .build();
    }
}
