package org.wwz.ai.application.agent.stream;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;

import java.util.ArrayList;
import java.util.List;

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
