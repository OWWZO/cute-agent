package org.wwz.ai.test.domain.handler;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;
import org.wwz.ai.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import org.wwz.ai.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.handler.BaseAgentStreamEventHandler;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;

import java.util.List;

import static org.wwz.ai.domain.agent.cute.model.constant.Constants.RUNNING;
import static org.wwz.ai.domain.agent.cute.model.constant.Constants.SUCCESS;

/**
 * {@code finish} 是可空 Boolean：没有 finish 的帧必须按“未结束”处理，不能自动拆箱 NPE。
 */
public class BaseAgentStreamEventHandlerFinishTest {

    private final TestableHandler handler = new TestableHandler();

    @Test
    public void treatsMissingFinishAsRunningInsteadOfThrowing() {
        AgentStreamResult result = handler.build("req-no-finish", null);

        Assert.assertFalse(result.isComplete());
        Assert.assertEquals(RUNNING, result.getStatus());
    }

    @Test
    public void keepsExplicitFinishTrue() {
        AgentStreamResult result = handler.build("req-finish", Boolean.TRUE);

        Assert.assertTrue(result.isComplete());
        Assert.assertEquals(SUCCESS, result.getStatus());
    }

    private static final class TestableHandler extends BaseAgentStreamEventHandler {

        private TestableHandler() {
            super(new ReplayProjector(new ToolInvocationProjectorRegistry(
                    List.of(), new DefaultToolInvocationProjector())));
        }

        private AgentStreamResult build(String requestId, Boolean finish) {
            return buildCanonicalIncrResult(
                    AgentExecutionCommand.builder().requestId(requestId).build(),
                    new AgentStreamAccumulator(),
                    AgentStreamEvent.builder()
                            .requestId(requestId)
                            .messageType("unknown_event")
                            .finish(finish)
                            .build());
        }
    }
}
