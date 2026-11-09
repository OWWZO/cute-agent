package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;

public class EvalCompactionCarryForwardCliTest {

    @Test
    public void evalUserIdIsolatedPerCaseRun() {
        String first = EvalCompactionCarryForwardCli.evalUserId(
                "0123456789abcdef0123456789abcdef");
        String second = EvalCompactionCarryForwardCli.evalUserId(
                "fedcba9876543210fedcba9876543210");

        Assert.assertNotEquals(first, second);
        Assert.assertTrue(first.length() <= 64);
        Assert.assertTrue(first.startsWith("eval-user-"));
    }

    @Test
    public void requestIdFitsExecutionLedgerColumn() {
        String requestId = EvalCompactionCarryForwardCli.requestId(
                "nightly-",
                "compaction-carry-forward.compressed-goal-constraints",
                "0123456789abcdef0123456789abcdef");

        Assert.assertTrue(requestId.length() <= 64);
        Assert.assertTrue(requestId.startsWith("nightly"));
        Assert.assertTrue(requestId.endsWith("0123456789abcdef01234567"));
        Assert.assertFalse(requestId.contains("-compact"));

        String longPrefix = EvalCompactionCarryForwardCli.requestId(
                "x".repeat(100),
                "compaction-carry-forward.compressed-goal-constraints",
                "0123456789abcdef0123456789abcdef");
        Assert.assertTrue(longPrefix.length() <= 64);
    }
}
