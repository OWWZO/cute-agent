package org.wwz.ai.application.agent.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionPrinterTest {

    @Test
    public void resultWithNullSerializedPayloadStillSendsTerminalFrame() {
        RecordingStream stream = new RecordingStream();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("request-1")
                .sessionId("session-1")
                .build();
        AgentSessionPrinter printer = new AgentSessionPrinter(stream, request, 1);

        printer.send("message-1", "result", null, null, null, true);

        Assert.assertEquals(1, stream.events.size());
        Assert.assertNull(stream.events.get(0).getResult());
        Assert.assertNotNull(stream.events.get(0).getResultMap());
    }

    @Test
    public void timingMapRehydrationKeepsFirstTokenAt() throws Exception {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(stream,
                AgentExecutionCommand.builder().requestId("request-1").build(), 1);
        LocalDateTime firstTokenAt = LocalDateTime.now().minusNanos(100_000_000L);
        Method convert = AgentSessionPrinter.class.getDeclaredMethod("toReplayTiming", Object.class);
        convert.setAccessible(true);

        ReplayTiming timing = (ReplayTiming) convert.invoke(printer,
                Map.of("firstTokenAt", firstTokenAt.toString()));

        Assert.assertEquals(firstTokenAt, timing.getFirstTokenAt());
    }

    private static final class RecordingStream implements AgentSessionStream {
        private final List<AgentStreamEvent> events = new ArrayList<>();

        @Override
        public void send(Object payload) {
            events.add((AgentStreamEvent) payload);
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
    }
}
