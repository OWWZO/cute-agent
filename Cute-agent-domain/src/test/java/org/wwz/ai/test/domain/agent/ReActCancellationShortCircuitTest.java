package org.wwz.ai.test.domain.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ReActAgent;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.cancel.RunControl;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 取消后不得再执行 act() 的副作用。
 */
public class ReActCancellationShortCircuitTest {

    @Test
    public void cancelledRunSkipsActEvenWhenThinkSaysAct() {
        AtomicInteger acts = new AtomicInteger();
        ProbeAgent agent = new ProbeAgent(acts);
        RunCancellation cancellation = new RunCancellation();
        agent.setContext(AgentContext.builder()
                .requestId("cancel-after-think")
                .control(new RunControl().withCancellation(cancellation))
                .build());
        cancellation.cancel(RunCancellation.REASON_USER_STOP);

        Assert.assertEquals("Terminated: User stopped", agent.step());
        Assert.assertEquals(0, acts.get());
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
    }

    private static final class ProbeAgent extends ReActAgent {
        private final AtomicInteger acts;

        private ProbeAgent(AtomicInteger acts) {
            this.acts = acts;
        }

        @Override
        public boolean think() {
            return true;
        }

        @Override
        public String act() {
            acts.incrementAndGet();
            return "acted";
        }
    }
}
