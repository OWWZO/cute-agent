package org.wwz.ai.domain.agent.ledger.timeline;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TimelineItemProjectionTest {

    @Test
    public void shouldUseStableLlmItemIdForStreamingDelta() {
        TimelineItemView item = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                9,
                event("agent_stream", Map.of(
                        "messageId", "msg-1",
                        "llmInvocationId", 42,
                        "result", "你好"
                )),
                "running",
                false
        );

        Assert.assertEquals("llm:42", item.getItemId());
        Assert.assertEquals("item.delta", item.getEventType());
        Assert.assertEquals("你好", item.getDelta());
    }

    @Test
    public void shouldKeepOneLogicalToolItemFromRunningToCompleted() {
        TimelineItemView running = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                10,
                event("tool_call", Map.of(
                        "messageId", "msg-tool",
                        "toolCallId", "call-1",
                        "status", "running"
                )),
                "running",
                false
        );
        TimelineItemView completed = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                11,
                event("tool_result", Map.of(
                        "messageId", "msg-tool",
                        "toolCallId", "call-1",
                        "status", "success"
                )),
                "success",
                false
        );

        Assert.assertEquals("tool:call-1", running.getItemId());
        Assert.assertEquals(running.getItemId(), completed.getItemId());
        Assert.assertEquals(TimelineItemStatus.RUNNING.name(), running.getStatus());
        Assert.assertEquals(TimelineItemStatus.COMPLETED.name(), completed.getStatus());
    }

    @Test
    public void shouldProjectRootResultAsRunTerminalItem() {
        TimelineItemView item = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                12,
                event("result", Map.of(
                        "messageId", "msg-result",
                        "requestId", "req-1",
                        "status", "success"
                )),
                "success",
                true
        );

        Assert.assertEquals("run:req-1:final", item.getItemId());
        Assert.assertEquals("run.completed", item.getEventType());
        Assert.assertEquals(TimelineItemPhase.FINAL.name(), item.getPhase());
    }

    @Test
    public void taskSummaryMustRemainANonTerminalSummaryItem() {
        TimelineItemView first = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                12,
                event("task_summary", Map.of(
                        "messageId", "summary-1",
                        "llmInvocationId", 41,
                        "status", "success"
                )),
                "success",
                true
        );
        TimelineItemView second = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                13,
                event("task_summary", Map.of(
                        "messageId", "summary-2",
                        "llmInvocationId", 42,
                        "status", "success"
                )),
                "success",
                true
        );

        Assert.assertEquals("summary:41", first.getItemId());
        Assert.assertEquals("summary:42", second.getItemId());
        Assert.assertNotEquals(first.getItemId(), second.getItemId());
        Assert.assertEquals("item.completed", first.getEventType());
        Assert.assertEquals(TimelineItemPhase.SUMMARY.name(), first.getPhase());
    }

    @Test
    public void artifactAndHitlIdsMustNotCollideWithTheirToolInvocation() {
        TimelineItemView artifact = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                13,
                event("file", Map.of(
                        "messageId", "msg-file",
                        "toolCallId", "call-1",
                        "artifactId", 88
                )),
                "success",
                false
        );
        TimelineItemView hitl = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                14,
                event("ask_user_question", Map.of(
                        "messageId", "msg-hitl",
                        "toolCallId", "call-1"
                )),
                "waiting",
                false
        );

        Assert.assertEquals("artifact:88", artifact.getItemId());
        Assert.assertEquals("hitl:call-1", hitl.getItemId());
        Assert.assertEquals(88, artifact.getPayload().get("artifactId"));
        Assert.assertNotEquals("tool:call-1", artifact.getItemId());
        Assert.assertNotEquals("tool:call-1", hitl.getItemId());
    }

    @Test
    public void completedHitlEventShouldNotStayWaiting() {
        TimelineItemView hitl = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                15,
                event("ask_user_question", Map.of(
                        "messageId", "msg-hitl",
                        "toolCallId", "call-1",
                        "status", "success"
                )),
                "success",
                true
        );

        Assert.assertEquals(TimelineItemStatus.COMPLETED.name(), hitl.getStatus());
    }

    @Test
    public void pendingHitlMustNotBeCompletedByFinishedFrame() {
        TimelineItemView hitl = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                15,
                event("ask_user_question", Map.of(
                        "messageId", "msg-hitl",
                        "toolCallId", "call-1",
                        "status", "pending"
                )),
                "success",
                true
        );

        Assert.assertEquals(TimelineItemStatus.WAITING_INPUT.name(), hitl.getStatus());
    }

    @Test
    public void answeredHitlMustWinOverPendingPersistenceStatus() {
        TimelineItemView hitl = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                15,
                event("ask_user_question", Map.of(
                        "messageId", "msg-hitl",
                        "toolCallId", "call-1",
                        "status", "answered",
                        "persistenceStatus", "RESUME_PENDING"
                )),
                "success",
                true
        );

        Assert.assertEquals(TimelineItemStatus.COMPLETED.name(), hitl.getStatus());
    }

    @Test
    public void envelopeLevelSubAgentLinkageMustBeProjected() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageType", "task");
        event.put("messageId", "msg-child");
        event.put("parentToolCallId", "call-parent");
        event.put("subAgentId", "agent-child");
        event.put("resultMap", new LinkedHashMap<>(Map.of(
                "messageType", "agent_stream",
                "messageId", "msg-child",
                "status", "success"
        )));

        TimelineItemView item = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                16,
                event,
                "success",
                false
        );

        Assert.assertEquals("tool:call-parent", item.getParentItemId());
        Assert.assertEquals("agent-child", item.getAgentId());
    }

    @Test
    public void nestedSubAgentResultMustNotBecomeRunTerminal() {
        TimelineItemView item = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                16,
                event("result", Map.of(
                        "messageId", "msg-child",
                        "parentToolUseId", "call-parent",
                        "subAgentId", "agent-child",
                        "status", "success"
                )),
                "success",
                false
        );

        Assert.assertEquals("subagent:agent-child:final", item.getItemId());
        Assert.assertEquals("item.completed", item.getEventType());
        Assert.assertEquals(TimelineItemPhase.INTERMEDIATE.name(), item.getPhase());
        Assert.assertEquals("tool:call-parent", item.getParentItemId());
    }

    @Test
    public void failedDirectoryStatusMustNotBeMaskedByCompletedEvent() {
        TimelineItemView event = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                20,
                event("tool_result", Map.of(
                        "messageId", "msg-tool",
                        "toolCallId", "call-1",
                        "status", "success"
                )),
                "success",
                false
        );
        TimelineItem directory = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("tool:call-1")
                .kind(TimelineItemKind.TOOL_CALL.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.FAILED.name())
                .orderKey(7L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(event), List.of(directory));

        Assert.assertEquals(TimelineItemStatus.FAILED.name(), merged.get(0).getStatus());
    }

    @Test
    public void directoryArtifactsMustNotDuplicateAProjectedFileEvent() {
        TimelineItemView fileEvent = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                16,
                event("file", Map.of(
                        "messageId", "msg-file",
                        "toolCallId", "call-1"
                )),
                "success",
                false
        );
        TimelineItem directoryArtifact = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("artifact:88")
                .parentItemId("tool:call-1")
                .kind(TimelineItemKind.ARTIFACT.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(9L)
                .artifactId(88L)
                .toolInvocationId(7L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(fileEvent),
                List.of(directoryArtifact)
        );

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals(fileEvent.getItemId(), merged.get(0).getItemId());
        Assert.assertEquals("tool:call-1", merged.get(0).getParentItemId());
    }

    @Test
    public void directoryOnlyArtifactMustWaitForLedgerPayloadAssembly() {
        TimelineItem directoryArtifact = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("artifact:88")
                .parentItemId("tool:call-1")
                .kind(TimelineItemKind.ARTIFACT.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(9L)
                .artifactId(88L)
                .toolInvocationId(7L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(), List.of(directoryArtifact));

        Assert.assertEquals(1, merged.size());
        Assert.assertNull(merged.get(0).getPayload().get("eventData"));
        Assert.assertEquals(88L, merged.get(0).getPayload().get("artifactId"));
        Assert.assertEquals(7L, merged.get(0).getPayload().get("toolInvocationId"));
    }

    @Test
    public void orphanArtifactDirectoryRowMustNotCreateFileCard() {
        TimelineItem orphan = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("artifact:deadbeef-dead-beef-dead-beefdeadbeef")
                .kind(TimelineItemKind.ARTIFACT.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(9L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(), List.of(orphan));

        Assert.assertTrue(merged.isEmpty());
    }

    @Test
    public void artifactDirectoryRowWithoutToolInvocationMustNotCreateFileCard() {
        TimelineItem orphan = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("artifact:89")
                .parentItemId("tool:call-1")
                .kind(TimelineItemKind.ARTIFACT.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(9L)
                .artifactId(89L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(), List.of(orphan));

        Assert.assertTrue(merged.isEmpty());
    }

    @Test
    public void directoryShouldOwnFirstNonZeroOrderAndTerminalStatus() {
        TimelineItemView event = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                2,
                event("tool_call", Map.of(
                        "messageId", "msg-tool",
                        "toolCallId", "call-1",
                        "status", "running"
                )),
                "running",
                false
        );
        TimelineItem directory = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("tool:call-1")
                .kind(TimelineItemKind.TOOL_CALL.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(7L)
                .subOrder(1)
                .updatedSeq(9L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(event),
                List.of(directory)
        );

        Assert.assertEquals(7L, merged.get(0).getOrderKey());
        Assert.assertEquals(9L, merged.get(0).getSeq());
        Assert.assertEquals(TimelineItemStatus.COMPLETED.name(), merged.get(0).getStatus());
    }

    @Test
    public void everyFrameWithSameItemIdMustReceiveDirectoryMetadata() {
        TimelineItemView first = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                1,
                event("deep_search", Map.of(
                        "messageId", "msg-search-1",
                        "toolCallId", "call-1",
                        "status", "running"
                )),
                "running",
                false
        );
        TimelineItemView last = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                2,
                event("deep_search", Map.of(
                        "messageId", "msg-search-2",
                        "toolCallId", "call-1",
                        "status", "success"
                )),
                "success",
                true
        );
        TimelineItem directory = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("tool:call-1")
                .kind(TimelineItemKind.TOOL_CALL.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(8L)
                .subOrder(2)
                .updatedSeq(12L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(first, last), List.of(directory));

        Assert.assertEquals(2, merged.size());
        Assert.assertTrue(merged.stream().allMatch(view -> view.getOrderKey() == 8L));
        Assert.assertTrue(merged.stream().allMatch(view -> view.getSubOrder() == 2));
        Assert.assertTrue(merged.stream().allMatch(
                view -> TimelineItemStatus.COMPLETED.name().equals(view.getStatus())));
    }

    @Test
    public void unmatchedViewMustStayBetweenItsMatchedNeighbours() {
        TimelineItemView first = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                1,
                event("tool_result", Map.of(
                        "messageId", "msg-1",
                        "toolCallId", "call-1",
                        "status", "success"
                )),
                "success",
                false
        );
        TimelineItemView notice = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                2,
                event("context_usage", Map.of("messageId", "msg-2")),
                "running",
                false
        );
        TimelineItemView last = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                3,
                event("tool_result", Map.of(
                        "messageId", "msg-3",
                        "toolCallId", "call-2",
                        "status", "success"
                )),
                "success",
                false
        );
        List<TimelineItem> directory = List.of(
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("tool:call-1")
                        .kind(TimelineItemKind.TOOL_CALL.name())
                        .phase(TimelineItemPhase.INTERMEDIATE.name())
                        .status(TimelineItemStatus.COMPLETED.name())
                        .orderKey(10L)
                        .build(),
                TimelineItem.builder()
                        .sessionId("session-1")
                        .runId("req-1")
                        .itemId("tool:call-2")
                        .kind(TimelineItemKind.TOOL_CALL.name())
                        .phase(TimelineItemPhase.INTERMEDIATE.name())
                        .status(TimelineItemStatus.COMPLETED.name())
                        .orderKey(20L)
                        .build()
        );

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(first, notice, last), directory);

        Assert.assertEquals(List.of("tool:call-1", "item:msg-2", "tool:call-2"),
                merged.stream().map(TimelineItemView::getItemId).toList());
        Assert.assertEquals(10L, merged.get(1).getOrderKey());
        Assert.assertTrue(merged.get(1).getSubOrder() > merged.get(0).getSubOrder());
    }

    @Test
    public void unmatchedViewMustNotInheritUnknownZeroOrderFromPreviousAnchor() {
        TimelineItemView notice = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                5,
                event("context_usage", Map.of("messageId", "msg-notice")),
                "running",
                false
        );
        TimelineItem hitl = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("hitl:call-1")
                .kind(TimelineItemKind.HITL.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.WAITING_INPUT.name())
                .orderKey(0L)
                .build();
        TimelineItem next = artifactDirectory(99L, "call-next", 20L);

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(notice), List.of(hitl, next));

        TimelineItemView noticeView = merged.stream()
                .filter(view -> "item:msg-notice".equals(view.getItemId()))
                .findFirst()
                .orElseThrow();
        Assert.assertTrue(noticeView.getOrderKey() > 0);
        Assert.assertEquals(20L, merged.get(merged.size() - 1).getOrderKey());
    }

    @Test
    public void allArtifactsFromAProjectedToolCallMustBeDeduplicated() {
        TimelineItemView fileEvent = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                1,
                event("file", Map.of(
                        "messageId", "msg-file",
                        "toolCallId", "call-1"
                )),
                "success",
                false
        );
        List<TimelineItem> artifacts = List.of(
                artifactDirectory(88L, "call-1", 8L),
                artifactDirectory(89L, "call-1", 9L)
        );

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(fileEvent), artifacts);

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals(fileEvent.getItemId(), merged.get(0).getItemId());
    }

    @Test
    public void waitingInputCanResumeThroughDirectoryRunningState() {
        TimelineItemView waiting = TimelineItemProjection.projectEvent(
                "req-1",
                "session-1",
                1,
                event("ask_user_question", Map.of(
                        "messageId", "msg-hitl",
                        "toolCallId", "call-1",
                        "status", "waiting"
                )),
                "success",
                false
        );
        TimelineItem directory = TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("hitl:call-1")
                .kind(TimelineItemKind.HITL.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.RUNNING.name())
                .orderKey(3L)
                .build();

        List<TimelineItemView> merged = TimelineItemProjection.mergeDirectory(
                List.of(waiting), List.of(directory));

        Assert.assertEquals(TimelineItemStatus.RUNNING.name(), merged.get(0).getStatus());
    }

    @Test
    public void eventIdMustUseRunScopeWhenSessionIsBlank() {
        Assert.assertEquals("run:7", TimelineItemProjection.eventId(null, 7, null));
        Assert.assertEquals("run:0:heartbeat",
                TimelineItemProjection.eventId(null, 0, "heartbeat"));
    }

    private TimelineItem artifactDirectory(long artifactId, String toolCallId, long orderKey) {
        return TimelineItem.builder()
                .sessionId("session-1")
                .runId("req-1")
                .itemId("artifact:" + artifactId)
                .parentItemId("tool:" + toolCallId)
                .kind(TimelineItemKind.ARTIFACT.name())
                .phase(TimelineItemPhase.INTERMEDIATE.name())
                .status(TimelineItemStatus.COMPLETED.name())
                .orderKey(orderKey)
                .artifactId(artifactId)
                .toolInvocationId(7L)
                .build();
    }

    private Map<String, Object> event(String nestedType, Map<String, Object> fields) {
        Map<String, Object> task = new LinkedHashMap<>(fields);
        task.put("messageType", nestedType);
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("toolCallId", fields.get("toolCallId"));
        task.put("resultMap", taskResult);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageType", "task");
        event.put("messageId", fields.get("messageId"));
        event.put("resultMap", task);
        return event;
    }
}
