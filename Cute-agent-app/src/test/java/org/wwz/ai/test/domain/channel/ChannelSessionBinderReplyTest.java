package org.wwz.ai.test.domain.channel;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.channel.inbound.ChannelSessionBinder;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 绑定处必须把最近一次入站消息写到会话，供异步终答读回。 */
public class ChannelSessionBinderReplyTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-12T02:00:00Z");

    @Test
    public void bindPersistsLatestInboundMessage() {
        IExecutionLedgerReadRepository readRepository =
                Mockito.mock(IExecutionLedgerReadRepository.class);
        IExecutionLedgerWriteRepository writeRepository =
                Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(readRepository.querySessionEntity("qq-c2c-user-1"))
                .thenReturn(DialogueSession.builder()
                        .sessionId("qq-c2c-user-1")
                        .bindingId(7L)
                        .channelChatType("C2C")
                        .channelTargetId("user-1")
                        .build());
        ChannelSessionBinder binder = new ChannelSessionBinder(readRepository, writeRepository);

        binder.bind("qq-c2c-user-1", 7L, "user-1", "C2C", "user-1",
                "inbound-msg-1", RECEIVED_AT);

        ArgumentCaptor<DialogueSessionUpsertRecord> captor =
                ArgumentCaptor.forClass(DialogueSessionUpsertRecord.class);
        Mockito.verify(writeRepository).upsertSession(captor.capture());
        Assert.assertEquals("inbound-msg-1", captor.getValue().getChannelLastMsgId());
        Assert.assertEquals(LocalDateTime.ofInstant(RECEIVED_AT, ZoneOffset.UTC),
                captor.getValue().getChannelLastReceivedAt());
    }
}
