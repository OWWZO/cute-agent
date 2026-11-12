package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;

import java.time.LocalDateTime;

/** 渠道会话的回复上下文必须能落库、读回，并且不被后续 run 启动清空。 */
public class SessionReplyContextPersistenceTest {

    private static final String SESSION_ID = "qq-c2c-reply-context";
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 11, 12, 2, 0);

    @Test
    public void upsertPersistsReplyContext() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();

        ctx.writeRepository.upsertSession(fullRecord("inbound-msg-1", RECEIVED_AT));

        var session = ctx.readRepository.querySessionEntity(SESSION_ID);
        Assert.assertEquals("inbound-msg-1", session.getChannelLastMsgId());
        Assert.assertEquals(RECEIVED_AT, session.getChannelLastReceivedAt());
    }

    @Test
    public void runStartKeepsExistingReplyContext() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.writeRepository.upsertSession(fullRecord("inbound-msg-1", RECEIVED_AT));

        ctx.writeRepository.upsertSessionOnRunStart(DialogueSessionUpsertRecord.builder()
                .sessionId(SESSION_ID)
                .userId("user-1")
                .rootSessionId(SESSION_ID)
                .title("继续")
                .status(1)
                .runCount(1)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(RECEIVED_AT.plusSeconds(1))
                .lastActiveAt(RECEIVED_AT.plusSeconds(1))
                .build());

        var session = ctx.readRepository.querySessionEntity(SESSION_ID);
        Assert.assertEquals("inbound-msg-1", session.getChannelLastMsgId());
        Assert.assertEquals(RECEIVED_AT, session.getChannelLastReceivedAt());
    }

    private static DialogueSessionUpsertRecord fullRecord(String msgId, LocalDateTime receivedAt) {
        return DialogueSessionUpsertRecord.builder()
                .sessionId(SESSION_ID)
                .userId("user-1")
                .bindingId(7L)
                .channelChatType("C2C")
                .channelTargetId("user-1")
                .channelLastMsgId(msgId)
                .channelLastReceivedAt(receivedAt)
                .title("QQ 会话")
                .status(1)
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .startedAt(RECEIVED_AT)
                .lastActiveAt(RECEIVED_AT)
                .build();
    }
}
