package org.wwz.ai.application.agent.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentStreamProjectionCompletionTest {

    @Test
    public void completeMustWaitForAnInFlightRuntimeEvent() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        List<String> lifecycle = new CopyOnWriteArrayList<>();
        AgentStreamEventHandler handler = (request, response, eventResult) -> {
            handlerEntered.countDown();
            try {
                if (!releaseHandler.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release handler");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return AgentStreamResult.builder()
                    .status("success")
                    .complete(false)
                    .contentDelta("delta")
                    .content("delta")
                    .frameType("result")
                    .eventData(Map.of())
                    .build();
        };
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-close-race")
                .agentType(AgentType.REACT.getValue())
                .build();
        RecordingStream downstream = new RecordingStream(lifecycle);
        AgentStreamProjection projection = new AgentStreamProjection(
                downstream, request, Map.of(AgentType.REACT, handler));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> send = executor.submit(() -> {
                try {
                    projection.send(AgentStreamEvent.builder()
                            .requestId("req-close-race")
                            .messageType("tool_thought")
                            .finish(false)
                            .resultMap(Map.of("agentType", AgentType.REACT.getValue()))
                            .build());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Assert.assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));

            Future<?> complete = executor.submit(projection::complete);
            downstream.completed.await(400, TimeUnit.MILLISECONDS);
            releaseHandler.countDown();

            send.get(2, TimeUnit.SECONDS);
            complete.get(2, TimeUnit.SECONDS);
            Assert.assertEquals(List.of("send", "complete"), lifecycle);
        } finally {
            releaseHandler.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void localObserverAbortMustNotSignalProjectionAbortWhenSessionBusIsActive() {
        AtomicBoolean abortHandlerCalled = new AtomicBoolean(false);
        AbortingStream localObserver = new AbortingStream();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-bus-abort")
                .sessionId("sess-bus-abort")
                .agentType(AgentType.REACT.getValue())
                .build();
        AgentStreamProjection projection = new AgentStreamProjection(
                localObserver,
                request,
                Map.of(),
                (sessionId, frame) -> {
                });
        projection.onAbort(() -> abortHandlerCalled.set(true));

        localObserver.abort();

        Assert.assertFalse(projection.isAborted());
        Assert.assertFalse("有会话总线时，本地观察连接断开不等于整条投影中断",
                abortHandlerCalled.get());
    }

    private static final class RecordingStream implements AgentSessionStream {
        private final List<String> lifecycle;
        private final CountDownLatch completed = new CountDownLatch(1);

        private RecordingStream(List<String> lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override
        public void send(Object payload) {
            lifecycle.add("send");
        }

        @Override
        public void complete() {
            lifecycle.add("complete");
            completed.countDown();
        }

        @Override
        public void completeWithError(Throwable throwable) {
            lifecycle.add("complete");
            completed.countDown();
        }

        @Override
        public void onAbort(Runnable abortHandler) {
        }

        @Override
        public boolean isAborted() {
            return false;
        }
    }

    private static final class AbortingStream implements AgentSessionStream {
        private final List<Runnable> abortHandlers = new CopyOnWriteArrayList<>();
        private boolean aborted;

        private void abort() {
            aborted = true;
            for (Runnable handler : abortHandlers) {
                handler.run();
            }
        }

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
            abortHandlers.add(abortHandler);
        }

        @Override
        public boolean isAborted() {
            return aborted;
        }
    }
}
