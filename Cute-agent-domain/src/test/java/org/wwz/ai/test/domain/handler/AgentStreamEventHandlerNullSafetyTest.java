package org.wwz.ai.test.domain.handler;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.handler.BaseAgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.handler.CanonicalAgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.util.List;
import java.util.Map;

/**
 * 流事件处理器的空值边界：缺累积器/缺 resultMap/处理异常都不能变成 NPE 或 null 帧。
 */
public class AgentStreamEventHandlerNullSafetyTest {

    private final ReplayProjector projector = new ReplayProjector(
            new ToolInvocationProjectorRegistry(List.of(), new DefaultToolInvocationProjector()));

    @Test
    public void missingAccumulatorShouldProduceEmptyEventDataInsteadOfNpe() {
        TestableHandler handler = new TestableHandler(projector);

        AgentStreamResult result = handler.build(
                AgentExecutionCommand.builder().requestId("req-1").build(),
                null,
                AgentStreamEvent.builder().requestId("req-1").messageType("task").build());

        Assert.assertNotNull(result);
        Assert.assertTrue(((Map<?, ?>) result.getEventData().get("eventData")).isEmpty());
    }

    @Test
    public void agentStreamWithoutResultMapShouldNotNpe() {
        TestableHandler handler = new TestableHandler(projector);

        AgentStreamResult result = handler.build(
                AgentExecutionCommand.builder().requestId("req-2").build(),
                new AgentStreamAccumulator(),
                AgentStreamEvent.builder()
                        .requestId("req-2")
                        .messageType("agent_stream")
                        .result("partial text")
                        .build());

        Assert.assertNotNull(result);
        Map<?, ?> eventData = (Map<?, ?>) result.getEventData().get("eventData");
        Assert.assertNotNull(eventData);
        Assert.assertFalse(eventData.containsKey("moreRounds"));
    }

    @Test
    public void canonicalHandlerShouldReturnErrorFrameInsteadOfNull() {
        CanonicalAgentStreamEventHandler handler = new CanonicalAgentStreamEventHandler(projector);

        AgentStreamResult result = handler.handle(
                null,
                AgentStreamEvent.builder().messageType("agent_stream").finish(Boolean.FALSE).build(),
                new AgentStreamAccumulator());

        Assert.assertNotNull(result);
        Assert.assertEquals("failed", result.getStatus());
        Assert.assertTrue(result.isComplete());
        Assert.assertNotNull(result.getErrorMessage());
    }

    private static final class TestableHandler extends BaseAgentStreamEventHandler {

        private TestableHandler(ReplayProjector replayProjector) {
            super(replayProjector);
        }

        private AgentStreamResult build(AgentExecutionCommand request,
                                        AgentStreamAccumulator accumulator,
                                        AgentStreamEvent event) {
            return buildCanonicalIncrResult(request, accumulator, event);
        }
    }
}
