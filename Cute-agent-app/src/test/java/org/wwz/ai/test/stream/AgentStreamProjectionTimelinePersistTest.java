package org.wwz.ai.test.stream;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.AgentTimelineEventProjector;
import org.wwz.ai.domain.agent.ledger.timeline.ITimelineItemRepository;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AgentStreamProjectionTimelinePersistTest {

    @Test
    public void upsertMustNotHoldProjectionLock() throws Exception {
        CountDownLatch upsertEntered = new CountDownLatch(1);
        CountDownLatch releaseUpsert = new CountDownLatch(1);
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        Mockito.doAnswer(invocation -> {
            upsertEntered.countDown();
            if (!releaseUpsert.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("upsert was not released");
            }
            return 1;
        }).when(repository).upsert(Mockito.any());

        AgentStreamProjection projection = newProjection(repository, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> send = executor.submit(() -> send(projection, event("msg-lock", 42, "库")));
            Assert.assertTrue("upsert should start",
                    upsertEntered.await(2, TimeUnit.SECONDS));

            Future<?> attach = executor.submit(() -> projection.addDownstream(new NoopStream(), 0L));
            try {
                attach.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Assert.fail("addDownstream was blocked while repository.upsert was running");
            } finally {
                releaseUpsert.countDown();
            }
            send.get(2, TimeUnit.SECONDS);
        } finally {
            releaseUpsert.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void enrichFieldsMustBeVisibleWhenFrameIsForwarded() throws Exception {
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        AtomicReference<AgentSessionStreamFrame> forwarded = new AtomicReference<>();
        AgentSessionEventBus eventBus = (sessionId, frame) -> forwarded.set(snapshot(frame));
        AgentStreamProjection projection = newProjection(repository, eventBus);

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
    public void upsertFailureMustKeepFrameAndAllowRetry() throws Exception {
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        AtomicInteger upsertCalls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (upsertCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return 1;
        }).when(repository).upsert(Mockito.any());
        List<Long> forwardedSeqs = new CopyOnWriteArrayList<>();
        AgentSessionEventBus eventBus =
                (sessionId, frame) -> forwardedSeqs.add(frame.getEventSeq());
        AgentStreamProjection projection = newProjection(repository, eventBus);

        send(projection, event("msg-retry", 42, "重试前"));
        Assert.assertEquals(List.of(1L), forwardedSeqs);

        send(projection, event("msg-retry", 42, "重试后"));
        Assert.assertEquals(List.of(1L, 2L), forwardedSeqs);
        Mockito.verify(repository, Mockito.times(2)).upsert(Mockito.any());
    }

    @Test
    public void laterFrameMustForwardWhileEarlierUpsertRunsAndWritesStayOrdered() throws Exception {
        CountDownLatch firstUpsertEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstUpsert = new CountDownLatch(1);
        AtomicInteger upsertCalls = new AtomicInteger();
        AtomicInteger activeUpserts = new AtomicInteger();
        AtomicInteger maxActiveUpserts = new AtomicInteger();
        List<Long> updatedSeqs = new CopyOnWriteArrayList<>();
        ITimelineItemRepository repository = Mockito.mock(ITimelineItemRepository.class);
        Mockito.doAnswer(invocation -> {
            int call = upsertCalls.incrementAndGet();
            maxActiveUpserts.accumulateAndGet(activeUpserts.incrementAndGet(), Math::max);
            try {
                TimelineItem item = invocation.getArgument(0);
                updatedSeqs.add(item.getUpdatedSeq());
                if (call == 1) {
                    firstUpsertEntered.countDown();
                    if (!releaseFirstUpsert.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first upsert was not released");
                    }
                }
                return 1;
            } finally {
                activeUpserts.decrementAndGet();
            }
        }).when(repository).upsert(Mockito.any());

        CountDownLatch secondFrameForwarded = new CountDownLatch(1);
        AgentSessionEventBus eventBus = (sessionId, frame) -> {
            if (frame.getEventSeq() == 2L) {
                secondFrameForwarded.countDown();
            }
        };
        AgentStreamProjection projection = newProjection(repository, eventBus);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> send(projection, event("msg-order-1", 41, "一")));
            Assert.assertTrue("first upsert should start",
                    firstUpsertEntered.await(2, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> send(projection, event("msg-order-2", 43, "二")));
            Assert.assertTrue("later frame should be forwarded before the first upsert finishes",
                    secondFrameForwarded.await(1, TimeUnit.SECONDS));
            Assert.assertEquals(1, upsertCalls.get());

            releaseFirstUpsert.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirstUpsert.countDown();
            executor.shutdownNow();
        }

        Assert.assertEquals(List.of(1L, 2L), updatedSeqs);
        Assert.assertEquals(1, maxActiveUpserts.get());
    }

    private AgentStreamProjection newProjection(ITimelineItemRepository repository,
                                                AgentSessionEventBus eventBus) {
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
                new AgentTimelineEventProjector(repository));
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
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("messageId", messageId);
        task.put("messageType", "agent_stream");
        task.put("llmInvocationId", llmInvocationId);
        task.put("result", text);
        task.put("status", "running");
        task.put("isFinal", false);
        task.put("finish", false);

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
                .build();
    }

    private static final class NoopStream implements AgentSessionStream {
        @Override
        public void send(Object payload) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable throwable) {
        }

        @Override
        public void onAbort(Runnable abortHandler) {
        }

        @Override
        public boolean isAborted() {
            return false;
        }
    }
}
