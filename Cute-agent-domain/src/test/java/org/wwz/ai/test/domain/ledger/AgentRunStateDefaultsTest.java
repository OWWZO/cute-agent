package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;

/**
 * run 级状态的两条构造路径都必须完整初始化。
 * <p>
 * {@code SubAgentContextFactory} 走 {@code new AgentRunState()}，其余调用方走 builder。
 * 任何一条路径漏掉默认值，都会在首次调用 {@code nextInvocationSeq()} 或
 * {@code clearExecutionPosition()} 时 NPE。
 */
public class AgentRunStateDefaultsTest {

    @Test
    public void noArgsConstructorInitializesRunScopedState() {
        assertInitialized(new AgentRunState());
    }

    @Test
    public void builderInitializesRunScopedState() {
        assertInitialized(AgentRunState.builder().build());
    }

    private static void assertInitialized(AgentRunState state) {
        Assert.assertNotNull(state.getNextLlmInvocationSeq());
        Assert.assertNotNull(state.getToolInvocationIdByToolCallId());
        Assert.assertNotNull(state.getCurrentAgentNameHolder());
        Assert.assertNotNull(state.getCurrentStepNoHolder());
        Assert.assertNotNull(state.getCurrentLlmInvocationIdHolder());
        Assert.assertEquals(1, state.nextInvocationSeq());
    }
}
