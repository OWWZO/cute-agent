package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionIdentity;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionStreamPort;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Phase 3 SSE 隔离开关的行为契约。
 * <p>
 * 只验证「子帧是否改道 + 父流摘要帧」，不真正启动 child run：
 * 端口抛错即可证明 open/close 的调用时机与参数，无需 LLM。
 */
public class SubAgentSessionStreamIsolationTest {

    @Test
    public void flagOnShouldOpenChildStreamBeforeRunAndCloseAfter() {
        RecordingStreamPort streamPort = new RecordingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        SubAgentResult result = runner.run(
                newParentContext(new NoopSessionPort(), streamPort, Boolean.TRUE, parentPrinter),
                "探索前端", "任务 prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");

        // 子流按确定性 childSessionId + 同一次派生的 childRequestId 打开一次
        Assert.assertEquals(1, streamPort.calls.size());
        StreamCall call = streamPort.calls.get(0);
        Assert.assertEquals("sub_agent-fixed", call.childSessionId);
        Assert.assertEquals("user-1", call.userId);
        Assert.assertNotNull("子 run requestId 必须与投影一致", call.requestId);
        Assert.assertTrue(call.requestId.length() <= 64);
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), call.agentType);

        // 终态收口：子流被关闭一次
        Assert.assertEquals(1, streamPort.closes.size());
        Assert.assertEquals("sub_agent-fixed", streamPort.closes.get(0));

        // 父流只留下两条自包含摘要帧，且先 start 后 end
        Assert.assertEquals(List.of("subagent_start", "subagent_end"), parentPrinter.messageTypes);
        Assert.assertEquals(List.of("sub_agent-fixed", "sub_agent-fixed"),
                parentPrinter.frameChildSessionIds);
        Assert.assertEquals(List.of(call.requestId, call.requestId),
                parentPrinter.frameChildRequestIds);

        // 结果仍带回 childSessionId（隔离流端口不可用时回退透传，不阻断子 Agent）
        Assert.assertEquals("sub_agent-fixed", result.getChildSessionId());
    }

    @Test
    public void childRunShouldBeInitializedBeforeStartFrame() {
        RecordingStreamPort streamPort = new RecordingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.createRun(Mockito.any())).thenAnswer(invocation -> {
            Assert.assertFalse("subagent_start 必须在 child run 初始化之后广播",
                    parentPrinter.messageTypes.contains("subagent_start"));
            return 7L;
        });
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        runner.run(
                newParentContext(new NoopSessionPort(), streamPort, Boolean.TRUE, parentPrinter, recorder),
                "探索前端", "任务 prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");

        Assert.assertEquals(List.of("subagent_start", "subagent_end"),
                parentPrinter.messageTypes);
    }

    @Test
    public void childRunInitializationFailureShouldCloseStreamWithoutStartFrame() {
        RecordingStreamPort streamPort = new RecordingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.createRun(Mockito.any()))
                .thenThrow(new IllegalStateException("ledger unavailable"));
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        try {
            runner.run(
                    newParentContext(new NoopSessionPort(), streamPort, Boolean.TRUE, parentPrinter, recorder),
                    "探索前端", "任务 prompt",
                    SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");
            Assert.fail("初始化失败必须向上抛出，不能广播一个不可查询的子会话");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("ledger unavailable", expected.getMessage());
        }

        Assert.assertTrue("start 前失败不得广播摘要帧", parentPrinter.messageTypes.isEmpty());
        Assert.assertEquals(List.of("sub_agent-fixed"), streamPort.closes);
    }

    @Test
    public void isolatedChildRunIsRegisteredBeforeStartFrameAndRemovedAfterFinish() {
        RecordingStreamPort streamPort = new RecordingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        parentPrinter.onMessageType = messageType -> {
            if ("subagent_start".equals(messageType)) {
                String childRequestId = streamPort.calls.get(0).requestId;
                Assert.assertTrue("start 帧广播前必须已有可观察的 child run",
                        registry.find(childRequestId).isPresent());
            }
        };
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        runner.run(
                newParentContext(
                        new NoopSessionPort(),
                        streamPort,
                        Boolean.TRUE,
                        parentPrinter,
                        null,
                        registry
                ),
                "探索前端", "任务 prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");

        Assert.assertFalse("child run 结束后必须从活跃索引移除",
                registry.find(streamPort.calls.get(0).requestId).isPresent());
    }

    @Test
    public void flagOffShouldNotEmitSubAgentSummaryFrames() {
        RecordingStreamPort streamPort = new RecordingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        // 开关缺省（cuteConfig 为 null）→ 不隔离，子流端口一次都不该被调用
        runner.run(newParentContext(new NoopSessionPort(), streamPort, null, parentPrinter),
                "探索前端", "任务 prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");

        Assert.assertEquals("flag 关闭时不得打开子会话流", 0, streamPort.calls.size());
        Assert.assertEquals("flag 关闭时不得关闭子会话流", 0, streamPort.closes.size());
        Assert.assertTrue("flag 关闭时父流不得出现子代理摘要帧",
                parentPrinter.messageTypes.isEmpty());
    }

    @Test
    public void streamPortFailureShouldFallBackNotAbortRun() {
        FailingStreamPort streamPort = new FailingStreamPort();
        RecordingPrinter parentPrinter = new RecordingPrinter();
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry());

        SubAgentResult result = runner.run(
                newParentContext(new NoopSessionPort(), streamPort, Boolean.TRUE, parentPrinter),
                "探索前端", "任务 prompt",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE, null, null, "agent-fixed");

        Assert.assertEquals(1, streamPort.calls.size());
        // 端口失败必须回退透传父流，不得中止子 Agent，也不得留下半截摘要帧
        Assert.assertFalse("会话流失败不应导致子 Agent 中止",
                result.getErrorMsg() != null && result.getErrorMsg().contains("会话流"));
        Assert.assertEquals("sub_agent-fixed", result.getChildSessionId());
        Assert.assertTrue("回退时父流不得残留子代理摘要帧",
                parentPrinter.messageTypes.isEmpty());
    }

    private static AgentContext newParentContext(SubAgentSessionPort sessionPort,
                                                 SubAgentSessionStreamPort streamPort,
                                                 Boolean isolatedFlag,
                                                 Printer printer) {
        return newParentContext(sessionPort, streamPort, isolatedFlag, printer, null);
    }

    private static AgentContext newParentContext(SubAgentSessionPort sessionPort,
                                                 SubAgentSessionStreamPort streamPort,
                                                 Boolean isolatedFlag,
                                                 Printer printer,
                                                 AgentExecutionRecorder recorder) {
        return newParentContext(sessionPort, streamPort, isolatedFlag, printer, recorder, null);
    }

    private static AgentContext newParentContext(SubAgentSessionPort sessionPort,
                                                 SubAgentSessionStreamPort streamPort,
                                                 Boolean isolatedFlag,
                                                 Printer printer,
                                                 AgentExecutionRecorder recorder,
                                                 ActiveAgentRunRegistry registry) {
        org.wwz.ai.domain.agent.cute.config.CuteConfig config = null;
        if (isolatedFlag != null) {
            config = new org.wwz.ai.domain.agent.cute.config.CuteConfig();
            try {
                java.lang.reflect.Field field = org.wwz.ai.domain.agent.cute.config.CuteConfig.class
                        .getDeclaredField("subagentIsolatedSession");
                field.setAccessible(true);
                field.set(config, isolatedFlag);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .cuteConfig(config)
                .subAgentSessionPort(sessionPort)
                .subAgentSessionStreamPort(streamPort)
                .activeAgentRunRegistry(registry)
                .build();

        return AgentContext.builder()
                .requestId("req-parent")
                .sessionId("parent-session")
                .userId("user-1")
                .query("q")
                .agentType(AgentType.PLAN_SOLVE.getValue())
                .printer(printer)
                .executionRecorder(recorder)
                .toolCollection(new ToolCollection())
                .runtimeDependencies(dependencies)
                .build();
    }

    private record StreamCall(String childSessionId,
                              String userId,
                              String requestId,
                              String queryText,
                              Integer agentType) {
    }

    private static final class NoopSessionPort implements SubAgentSessionPort {
        @Override
        public void ensureChildSession(SubAgentSessionIdentity identity) {
            // 身份落库成功路径
        }
    }

    private static final class RecordingStreamPort implements SubAgentSessionStreamPort {
        private final List<StreamCall> calls = new ArrayList<>();
        private final List<String> closes = new ArrayList<>();

        @Override
        public Printer openChildSessionStream(String childSessionId,
                                              String userId,
                                              String requestId,
                                              String queryText,
                                              Integer agentType) {
            calls.add(new StreamCall(childSessionId, userId, requestId, queryText, agentType));
            return new RecordingPrinter();
        }

        @Override
        public void closeChildSessionStream(String childSessionId, Printer printer) {
            closes.add(childSessionId);
        }
    }

    private static final class FailingStreamPort implements SubAgentSessionStreamPort {
        private final List<StreamCall> calls = new ArrayList<>();

        @Override
        public Printer openChildSessionStream(String childSessionId,
                                              String userId,
                                              String requestId,
                                              String queryText,
                                              Integer agentType) {
            calls.add(new StreamCall(childSessionId, userId, requestId, queryText, agentType));
            throw new IllegalStateException("stream bus down");
        }

        @Override
        public void closeChildSessionStream(String childSessionId, Printer printer) {
        }
    }

    private static final class RecordingPrinter implements Printer {
        private final List<String> messageTypes = new ArrayList<>();
        private final List<String> frameChildSessionIds = new ArrayList<>();
        private final List<String> frameChildRequestIds = new ArrayList<>();
        private Consumer<String> onMessageType;

        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
            record(messageType, message);
        }

        @Override
        public void send(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            record(messageType, message);
            if (extraResultMap != null && extraResultMap.get("childSessionId") != null) {
                frameChildSessionIds.add(String.valueOf(extraResultMap.get("childSessionId")));
            }
            if (extraResultMap != null && extraResultMap.get("childRequestId") != null) {
                frameChildRequestIds.add(String.valueOf(extraResultMap.get("childRequestId")));
            }
        }

        private void record(String messageType, Object message) {
            messageTypes.add(messageType);
            if (onMessageType != null) {
                onMessageType.accept(messageType);
            }
        }

        @Override
        public void send(String messageType, Object message) {
            record(messageType, message);
        }

        @Override
        public void send(String messageType, Object message, String digitalEmployee) {
            record(messageType, message);
        }

        @Override
        public void send(String messageId, String messageType, Object message, Boolean isFinal) {
            record(messageType, message);
        }

        @Override
        public void sendWithResultMap(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, Boolean isFinal) {
            record(messageType, message);
        }

        @Override
        public void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap) {
            record(messageType, message);
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(AgentType agentType) {
        }
    }
}
