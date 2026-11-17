package org.wwz.ai.test.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.AgentTimelineFrameEnricher;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentStreamProjectionTimelineEnvelopeTest {

    @Test
    public void enrichFieldsMustBeVisibleWhenFrameIsForwarded() {
        AtomicReference<AgentSessionStreamFrame> forwarded = new AtomicReference<>();
        AgentSessionEventBus eventBus = (sessionId, frame) -> forwarded.set(snapshot(frame));
        AgentStreamProjection projection = newProjection(eventBus);

        send(projection, event("msg-fields", 42, "你"));

        AgentSessionStreamFrame frame = forwarded.get();
        Assert.assertNotNull(frame);
        Assert.assertEquals(TimelineItemProjection.PROTOCOL_VERSION, frame.getProtocolVersion());
        Assert.assertEquals("item.delta", frame.getEventType());
        Assert.assertNotNull(frame.getEventId());
        Assert.assertEquals("req-1", frame.getRunId());
        Assert.assertEquals("llm:42", frame.getItemId());
        Assert.assertEquals(1L, frame.getSeq());
        Assert.assertEquals(1L, frame.getEventSeq());
    }

    @Test
    public void fileEventMustRemainTransientWhenEnriched() {
        AtomicReference<AgentSessionStreamFrame> forwarded = new AtomicReference<>();
        AgentSessionEventBus eventBus = (sessionId, frame) -> forwarded.set(snapshot(frame));
        AgentStreamProjection projection = newProjection(eventBus);

        send(projection, event("msg-file", 43, "report.md", "file"));

        AgentSessionStreamFrame frame = forwarded.get();
        Assert.assertNotNull(frame);
        // 实时 file 帧允许携带临时展示身份；该帧不会写入 ai_agent_timeline_item。
        Assert.assertEquals("artifact:call-file", frame.getItemId());
        Assert.assertEquals("file", frame.getResultMap().get("eventData") instanceof Map<?, ?> eventData
                ? ((Map<?, ?>) ((Map<?, ?>) eventData).get("resultMap")).get("messageType")
                : null);
    }

    private AgentStreamProjection newProjection(AgentSessionEventBus eventBus) {
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .agentType(AgentType.REACT.getValue())
                .build();
        return new AgentStreamProjection(
                null,
                request,
                Map.of(AgentType.REACT, handler()),
                eventBus,
                null,
                new AgentTimelineFrameEnricher());
    }

    private AgentStreamEventHandler handler() {
        return (request, response, accumulator) -> AgentStreamResult.builder()
                .status("running")
                .complete(false)
                .contentDelta("delta")
                .content("delta")
                .frameType("result")
                .eventData(response.getResultMap())
                .build();
    }

    private AgentStreamEvent event(String messageId, long llmInvocationId, String text) {
        return event(messageId, llmInvocationId, text, "agent_stream");
    }

    private AgentStreamEvent event(String messageId, long llmInvocationId, String text, String messageType) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("messageId", messageId);
        task.put("messageType", messageType);
        task.put("llmInvocationId", llmInvocationId);
        task.put("result", text);
        task.put("status", "running");
        task.put("isFinal", false);
        task.put("finish", false);
        if ("file".equals(messageType)) {
            task.put("toolCallId", "call-file");
            task.put("fileName", text);
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageType", "task");
        eventData.put("messageId", messageId);
        eventData.put("resultMap", task);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("eventData", eventData);
        return AgentStreamEvent.builder()
                .requestId("req-1")
                .messageId(messageId)
                .messageType("task")
                .isFinal(false)
                .finish(false)
                .resultMap(resultMap)
                .build();
    }

    private void send(AgentStreamProjection projection, AgentStreamEvent event) {
        try {
            projection.send(event);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentSessionStreamFrame snapshot(AgentSessionStreamFrame frame) {
        return AgentSessionStreamFrame.builder()
                .protocolVersion(frame.getProtocolVersion())
                .eventType(frame.getEventType())
                .eventId(frame.getEventId())
                .runId(frame.getRunId())
                .itemId(frame.getItemId())
                .seq(frame.getSeq())
                .eventSeq(frame.getEventSeq())
                .resultMap(frame.getResultMap())
                .build();
    }
}
