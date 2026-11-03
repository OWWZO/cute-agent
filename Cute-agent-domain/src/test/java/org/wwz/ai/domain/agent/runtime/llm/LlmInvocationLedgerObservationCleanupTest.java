package org.wwz.ai.domain.agent.runtime.llm;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;

/**
 * fail-open 路径同样要清理线程级 prompt 观测，避免污染下一次模型调用。
 */
public class LlmInvocationLedgerObservationCleanupTest {

    @Test
    public void disabledHandleStillClearsThreadLocalObservation() {
        LlmPromptObservability.restore(LlmPromptObservability.ObservationBundle.builder()
                .cacheStatus("STALE")
                .build());
        try {
            LlmInvocationLedger ledger = new LlmInvocationLedger(null, "test-model");
            ledger.write(LlmInvocationLedger.Handle.disabled(ReplayTiming.startRuntime()),
                    ExecutionLedgerConstants.STATUS_SUCCESS, null, null, 0, null, null, null);

            Assert.assertNull("无活跃 run 时也必须清掉上一轮观测快照",
                    LlmPromptObservability.current());
        } finally {
            LlmPromptObservability.clear();
        }
    }
}
