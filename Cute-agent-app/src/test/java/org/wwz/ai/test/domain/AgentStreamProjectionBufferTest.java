package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投影回放缓冲的清理规则回归测试。
 * <p>
 * 规则：只按「落库边界」清理。某轮 LLM 调用 / 某个流式工具还在产出时，delta 帧是账本
 * 盲区的唯一副本，必须完整保留；收到该组 {@code isFinal=true} 末帧后才可清中间帧，
 * 末帧自身保留（它是全文快照）。
 * <p>
 * 这里刻意断言「没有帧数上限」：任何按帧数裁剪都会在长思考 / 长工具流时丢掉前半段。
 */
public class AgentStreamProjectionBufferTest {

    @Test
    public void shouldKeepAllDeltasWhileStreamStillRunning() {
        AgentStreamProjection projection = newProjection();

        // 300 帧 delta，远超旧的 512 上限的一半以上，且全程没有末帧
        for (int i = 0; i < 300; i++) {
            offer(projection, llmDelta("msg-1", "agent_stream", false, "delta-" + i));
        }

        Assert.assertEquals(300, bufferSize(projection));
        Assert.assertEquals("delta-0", firstBuffered(projection));
    }

    @Test
    public void shouldKeepEveryFrameBeyondLegacyFiveHundredTwelveCap() {
        AgentStreamProjection projection = newProjection();

        // 旧实现在第 513 帧起挤掉最老的帧；新规则下必须全部保留
        for (int i = 0; i < 700; i++) {
            offer(projection, llmDelta("msg-1", "agent_stream", false, "delta-" + i));
        }

        Assert.assertEquals(700, bufferSize(projection));
        Assert.assertEquals("delta-0", firstBuffered(projection));
    }

    @Test
    public void shouldPruneDeltasAfterFinalFrameButKeepTheSnapshot() {
        AgentStreamProjection projection = newProjection();

        offer(projection, llmDelta("msg-1", "agent_stream", false, "a"));
        offer(projection, llmDelta("msg-1", "agent_stream", false, "b"));
        offer(projection, llmDelta("msg-1", "agent_stream", true, "ab"));

        // 中间 delta 清掉，只留末帧全文快照
        Assert.assertEquals(1, bufferSize(projection));
        Assert.assertEquals("ab", firstBuffered(projection));
    }

    @Test
    public void shouldNotPruneOtherMessagesWhenOneRoundCompletes() {
        AgentStreamProjection projection = newProjection();

        offer(projection, llmDelta("msg-1", "agent_stream", false, "a"));
        offer(projection, llmDelta("msg-2", "agent_stream", false, "x"));
        offer(projection, llmDelta("msg-2", "agent_stream", false, "y"));
        // msg-1 收口，msg-2 仍在流式
        offer(projection, llmDelta("msg-1", "agent_stream", true, "a"));

        Assert.assertEquals(3, bufferSize(projection));
        Assert.assertEquals("x", firstBuffered(projection));
    }

    @Test
    public void shouldPruneReasoningDeltasByTheirOwnFinalFrame() {
        AgentStreamProjection projection = newProjection();

        offer(projection, llmDelta("reason-1", "llm_reasoning", false, "思考1"));
        offer(projection, llmDelta("reason-1", "llm_reasoning", false, "思考2"));
        offer(projection, llmDelta("reason-1", "llm_reasoning", true, "思考1思考2"));

        Assert.assertEquals(1, bufferSize(projection));
        Assert.assertEquals("思考1思考2", firstBuffered(projection));
    }

    @Test
    public void shouldPruneStreamingToolFramesByToolCallId() {
        AgentStreamProjection projection = newProjection();

        offer(projection, toolFrame("call-1", "deep_search", false, "search"));
        offer(projection, toolFrame("call-1", "deep_search", false, "extend"));
        offer(projection, toolFrame("call-2", "deep_search", false, "other"));
        // call-1 收口，call-2 仍在跑
        offer(projection, toolFrame("call-1", "deep_search", true, "final-report"));

        Assert.assertEquals(2, bufferSize(projection));
        Assert.assertEquals("other", firstBuffered(projection));
    }

    @Test
    public void shouldNeverPruneTimelineSkeletonFrames() {
        AgentStreamProjection projection = newProjection();

        // tool_call / tool_result 是时间线骨架，即使 isFinal 也不参与清理
        offer(projection, skeletonFrame("tool_call", "call-1", true));
        offer(projection, skeletonFrame("tool_call", "call-1", true));

        Assert.assertEquals(2, bufferSize(projection));
    }

    @Test
    public void shouldIgnoreHeartbeat() {
        AgentStreamProjection projection = newProjection();

        offer(projection, AgentSessionStreamFrame.builder()
                .packageType("heartbeat")
                .resultMap(new HashMap<>())
                .build());

        Assert.assertEquals(0, bufferSize(projection));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private AgentStreamProjection newProjection() {
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-buffer")
                .sessionId("session-buffer")
                .agentType(AgentType.REACT.getValue())
                .build();
        return new AgentStreamProjection(null, request, Map.of());
    }

    private void offer(AgentStreamProjection projection, AgentSessionStreamFrame frame) {
        try {
            Method method = AgentStreamProjection.class
                    .getDeclaredMethod("offerReplayBuffer", AgentSessionStreamFrame.class);
            method.setAccessible(true);
            method.invoke(projection, frame);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private int bufferSize(AgentStreamProjection projection) {
        return ((Deque<AgentSessionStreamFrame>) readBuffer(projection)).size();
    }

    @SuppressWarnings("unchecked")
    private String firstBuffered(AgentStreamProjection projection) {
        AgentSessionStreamFrame first = ((Deque<AgentSessionStreamFrame>) readBuffer(projection))
                .peekFirst();
        return first == null ? null : eventText(first);
    }

    private Object readBuffer(AgentStreamProjection projection) {
        try {
            java.lang.reflect.Field field = AgentStreamProjection.class
                    .getDeclaredField("replayBuffer");
            field.setAccessible(true);
            return field.get(projection);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private String eventText(AgentSessionStreamFrame frame) {
        Map<String, Object> eventData = eventData(frame);
        if (eventData == null) {
            return null;
        }
        Object value = eventData.get("result");
        if (value == null) {
            value = eventData.get("toolThought");
        }
        if (value == null) {
            value = eventData.get("reasoningContent");
        }
        if (value == null && eventData.get("resultMap") instanceof Map<?, ?> nested) {
            value = nested.get("toolCallId");
        }
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventData(AgentSessionStreamFrame frame) {
        Object resultMap = frame.getResultMap();
        if (!(resultMap instanceof Map<?, ?> outer)) {
            return null;
        }
        Object eventData = outer.get("eventData");
        return eventData instanceof Map<?, ?> inner ? (Map<String, Object>) inner : null;
    }

    /** 模拟一轮 LLM 的流式帧：messageId 分组，isFinal 表示该轮已落库。 */
    private AgentSessionStreamFrame llmDelta(String messageId,
                                             String messageType,
                                             boolean isFinal,
                                             String text) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("messageId", messageId);
        eventData.put("messageType", messageType);
        eventData.put("isFinal", isFinal);
        if ("llm_reasoning".equals(messageType)) {
            eventData.put("reasoningContent", text);
        } else {
            eventData.put("result", text);
        }
        return wrap(eventData);
    }

    /** 模拟流式工具帧：toolCallId 分组。 */
    private AgentSessionStreamFrame toolFrame(String toolCallId,
                                              String messageType,
                                              boolean isFinal,
                                              String text) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("messageId", toolCallId + "-" + text);
        eventData.put("messageType", messageType);
        eventData.put("isFinal", isFinal);
        eventData.put("result", text);
        eventData.put("resultMap", new HashMap<>(Map.of("toolCallId", toolCallId)));
        return wrap(eventData);
    }

    /** 时间线骨架帧：不参与清理。 */
    private AgentSessionStreamFrame skeletonFrame(String messageType,
                                                  String toolCallId,
                                                  boolean isFinal) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("messageId", "skeleton-" + toolCallId);
        eventData.put("messageType", messageType);
        eventData.put("isFinal", isFinal);
        eventData.put("resultMap", new HashMap<>(Map.of("toolCallId", toolCallId)));
        return wrap(eventData);
    }

    private AgentSessionStreamFrame wrap(Map<String, Object> eventData) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("eventData", eventData);
        return AgentSessionStreamFrame.builder()
                .packageType("result")
                .resultMap(resultMap)
                .build();
    }
}
