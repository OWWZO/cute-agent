package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 终态推导：用户取消必须无条件优先于超时。
 */
public class ExecutionLedgerConstantsTest {

    @Test
    public void cancellationWinsOverNestedTimeout() {
        CancellationException cancelled = new CancellationException("用户停止");
        cancelled.initCause(new TimeoutException("socket read timed out"));

        Assert.assertEquals(ExecutionLedgerConstants.STATUS_STOPPED,
                ExecutionLedgerConstants.resolveFailureStatus(cancelled));
    }

    @Test
    public void interruptionWinsOverNestedTimeout() {
        InterruptedException interrupted = new InterruptedException("interrupted");
        interrupted.initCause(new TimeoutException("downstream timeout"));

        Assert.assertEquals(ExecutionLedgerConstants.STATUS_STOPPED,
                ExecutionLedgerConstants.resolveFailureStatus(interrupted));
    }

    @Test
    public void timeoutOnlyMapsToTimeout() {
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_TIMEOUT,
                ExecutionLedgerConstants.resolveFailureStatus(new TimeoutException("slow")));
    }

    @Test
    public void plainFailureMapsToFailed() {
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_FAILED,
                ExecutionLedgerConstants.resolveFailureStatus(new IllegalStateException("boom")));
    }
}
