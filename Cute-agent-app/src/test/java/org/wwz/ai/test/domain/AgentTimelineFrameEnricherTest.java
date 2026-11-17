package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentTimelineFrameEnricher;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemView;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentTimelineFrameEnricherTest {

    @Test
    public void shouldEnrichEveryStreamingFrameWithoutPersisting() {
        AgentTimelineFrameEnricher enricher = new AgentTimelineFrameEnricher();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .build();

        AgentSessionStreamFrame first = frame(1, "running", false, "你");
        AgentSessionStreamFrame second = frame(2, "running", false, "好");
        AgentSessionStreamFrame completed = frame(3, "success", true, "你好");

        TimelineItemView firstItem = enricher.enrich(first, request);
        TimelineItemView secondItem = enricher.enrich(second, request);
        TimelineItemView completedItem = enricher.enrich(completed, request);

        Assert.assertEquals("llm:42", firstItem.getItemId());
        Assert.assertEquals("llm:42", secondItem.getItemId());
        Assert.assertEquals("llm:42", completedItem.getItemId());
        Assert.assertEquals("item.completed", completed.getEventType());
    }

    @Test
    public void nestedSubAgentResultMustStayOutOfParentTimeline() {
        AgentTimelineFrameEnricher enricher = new AgentTimelineFrameEnricher();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .build();

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("messageId", "msg-child");
        task.put("messageType", "result");
        task.put("parentToolUseId", "call-parent");
        task.put("subAgentId", "agent-child");
        task.put("status", "success");
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageType", "task");
        eventData.put("messageId", "msg-child");
        eventData.put("resultMap", task);
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("eventData", eventData);
        AgentSessionStreamFrame frame = AgentSessionStreamFrame.builder()
                .status("success")
                .finished(false)
                .packageType("task")
                .eventSeq(4)
                .resultMap(resultMap)
                .build();

        Assert.assertNull(enricher.enrich(frame, request));
        Assert.assertNull(frame.getEventType());
    }

    @Test
    public void isolatedSubAgentSessionMustKeepItsOwnNestedDetail() {
        AgentTimelineFrameEnricher enricher = new AgentTimelineFrameEnricher();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-child")
                .sessionId("sub_agent-child")
                .timelineRootSession(true)
                .build();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("messageId", "msg-child");
        task.put("messageType", "result");
        task.put("parentToolUseId", "call-parent");
        task.put("subAgentId", "agent-child");
        task.put("status", "success");
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageType", "task");
        eventData.put("messageId", "msg-child");
        eventData.put("resultMap", task);
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("eventData", eventData);
        AgentSessionStreamFrame frame = AgentSessionStreamFrame.builder()
                .status("success")
                .finished(true)
                .eventSeq(5)
                .resultMap(resultMap)
                .build();

        TimelineItemView item = enricher.enrich(frame, request);

        Assert.assertNotNull(item);
        Assert.assertEquals("item.completed", frame.getEventType());
        Assert.assertEquals("INTERMEDIATE", frame.getPhase());
        Assert.assertTrue(frame.isTransientItem());
    }

    private AgentSessionStreamFrame frame(long seq, String status, boolean finished, String text) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("messageId", "msg-1");
        task.put("messageType", "agent_stream");
        task.put("llmInvocationId", 42);
        task.put("result", text);
        task.put("status", status);
        task.put("isFinal", finished);
        task.put("finish", finished);

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageType", "task");
        eventData.put("messageId", "msg-1");
        eventData.put("resultMap", task);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("eventData", eventData);
        return AgentSessionStreamFrame.builder()
                .status(status)
                .finished(finished)
                .packageType("task")
                .eventSeq(seq)
                .resultMap(resultMap)
                .build();
    }
}
