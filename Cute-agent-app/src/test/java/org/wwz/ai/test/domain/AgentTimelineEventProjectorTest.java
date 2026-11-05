package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentTimelineEventProjector;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentTimelineEventProjectorTest {

    @Test
    public void shouldWriteStreamingItemOnceAndUpdateOnCompletion() {
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        AgentTimelineEventProjector projector = new AgentTimelineEventProjector(repository);
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-1")
                .sessionId("session-1")
                .build();

        AgentSessionStreamFrame first = frame(1, "running", false, "你");
        AgentSessionStreamFrame second = frame(2, "running", false, "好");
        AgentSessionStreamFrame completed = frame(3, "success", true, "你好");

        Set<String> writtenItems = ConcurrentHashMap.newKeySet();
        projector.project(first, request, writtenItems);
        projector.project(second, request, writtenItems);
        Mockito.verify(repository, Mockito.times(1)).upsert(Mockito.any());

        projector.project(completed, request, writtenItems);
        Mockito.verify(repository, Mockito.times(2)).upsert(Mockito.any());
        Assert.assertEquals("llm:42", completed.getItemId());
        Assert.assertEquals("item.completed", completed.getEventType());
    }

    @Test
    public void nestedSubAgentResultMustStayOutOfParentTimeline() {
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        AgentTimelineEventProjector projector = new AgentTimelineEventProjector(repository);
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

        projector.project(frame, request, ConcurrentHashMap.newKeySet());

        Mockito.verifyNoInteractions(repository);
        Assert.assertNull(frame.getEventType());
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
