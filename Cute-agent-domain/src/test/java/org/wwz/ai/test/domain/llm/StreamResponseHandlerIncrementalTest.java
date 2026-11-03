package org.wwz.ai.test.domain.llm;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.LlmStreamEvent;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.printer.Printer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 增量扫描改动的回归测试：marker 跨 chunk、累积型 reasoning 都保持原有可见语义。
 */
public class StreamResponseHandlerIncrementalTest {

    @Test
    public void hiddenMarkerSplitAcrossChunksStillCutsVisibleContent() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = AgentContext.builder()
                .requestId("req-marker")
                .isStream(true)
                .streamMessageType("tool_thought")
                .printer(printer)
                .build();

        String result = handler.handleStringStream(
                context,
                new ScriptedStreamCall(List.of(content("hello"), content("``"), content("`json ignored"))),
                "```json",
                true,
                true
        ).get(5, TimeUnit.SECONDS);

        Assert.assertEquals("hello```json ignored", result);
        Assert.assertTrue("marker 跨 chunk 时最终快照必须只包含 marker 之前的内容",
                printer.messages.stream().anyMatch(m -> "hello".equals(m.message) && Boolean.TRUE.equals(m.isFinal)));
        Assert.assertFalse("marker 之后的内容不得推给客户端",
                printer.messages.stream().anyMatch(m -> String.valueOf(m.message).contains("ignored")));
    }

    @Test
    public void cumulativeReasoningChunksKeepOnlyDelta() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        AgentContext context = AgentContext.builder()
                .requestId("req-reasoning")
                .build();

        LLM.ToolCallResponse response = handler.handleToolCallStream(
                context,
                new ScriptedStreamCall(List.of(
                        reasoning("先"),
                        reasoning("先想"),
                        reasoning("先想清楚"))),
                System.currentTimeMillis() - 1,
                false
        ).get(5, TimeUnit.SECONDS);

        Assert.assertEquals("先想清楚", response.getReasoningContent());
    }

    private static LlmStreamEvent content(String text) {
        return LlmStreamEvent.builder().content(text).build();
    }

    private static LlmStreamEvent reasoning(String text) {
        return LlmStreamEvent.builder().reasoningContent(text).build();
    }

    private static final class ScriptedStreamCall implements LlmCompletionPort.StreamCall {
        private final List<LlmStreamEvent> events;

        private ScriptedStreamCall(List<LlmStreamEvent> events) {
            this.events = events;
        }

        @Override
        public void subscribe(Consumer<LlmStreamEvent> onEvent,
                              Consumer<Throwable> onError,
                              Runnable onComplete) {
            events.forEach(onEvent);
            onComplete.run();
        }

        @Override
        public void cancel() {
        }
    }

    private static final class RecordingPrinter implements Printer {
        private final List<Message> messages = new ArrayList<>();

        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
            messages.add(new Message(messageType, message, isFinal));
        }

        @Override
        public void send(String messageId, String messageType, Object message,
                         Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            messages.add(new Message(messageType, message, isFinal));
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(AgentType agentType) {
        }
    }

    private record Message(String messageType, Object message, Boolean isFinal) {
    }
}
