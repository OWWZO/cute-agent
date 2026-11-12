package org.wwz.ai.test.domain;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPendingHint;

/** 提示的清空与写入、快照并发语义；数据库仍是唯一事实源。 */
public class SessionInboxPendingHintTest {

    @Before
    public void setUp() {
        SessionInboxPendingHint.clearAll();
    }

    @After
    public void tearDown() {
        SessionInboxPendingHint.clearAll();
    }

    @Test
    public void cleanHintOnlyAppliesUntilAWriterArrives() {
        Assert.assertTrue(SessionInboxPendingHint.mayHavePending("s1"));
        Assert.assertTrue(SessionInboxPendingHint.markCleanIfUnchanged(
                "s1", SessionInboxPendingHint.snapshot()));
        Assert.assertFalse(SessionInboxPendingHint.mayHavePending("s1"));

        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        Assert.assertFalse(SessionInboxPendingHint.markCleanIfUnchanged(
                "s1", SessionInboxPendingHint.snapshot()));
        SessionInboxPendingHint.finishWrite("s1", write, true);

        Assert.assertTrue(SessionInboxPendingHint.mayHavePending("s1"));
        Assert.assertFalse(SessionInboxPendingHint.markCleanIfUnchanged("s1", write));
    }

    @Test
    public void staleEmptyClaimCannotCoverAConcurrentWrite() {
        SessionInboxPendingHint.WriteState beforeClaim = SessionInboxPendingHint.snapshot();
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        SessionInboxPendingHint.finishWrite("s1", write, true);

        Assert.assertFalse(SessionInboxPendingHint.markCleanIfUnchanged("s1", beforeClaim));
        Assert.assertTrue(SessionInboxPendingHint.mayHavePending("s1"));
        Assert.assertFalse(SessionInboxPendingHint.markCleanIfUnchanged(
                "s1", SessionInboxPendingHint.snapshot()));

        SessionInboxPendingHint.markPending("s1");
        Assert.assertTrue(SessionInboxPendingHint.mayHavePending("s1"));
    }

    @Test
    public void externalWritesDisableCleanHint() {
        String sessionId = "s-external";
        SessionInboxPendingHint.markCleanIfUnchanged(
                sessionId, SessionInboxPendingHint.snapshot());
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        SessionInboxPendingHint.finishWrite(sessionId, write, true);

        Assert.assertTrue(SessionInboxPendingHint.externalWritesPossible());
        Assert.assertFalse("外部写入后空 claim 不得再标 clean",
                SessionInboxPendingHint.markCleanIfUnchanged(
                        sessionId, SessionInboxPendingHint.snapshot()));
    }
}
