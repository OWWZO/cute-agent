package org.wwz.ai.test.domain.channel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.channel.outbound.ChannelArtifactResolver;
import org.wwz.ai.application.agent.channel.outbound.ChannelTerminalPushService;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.port.ChannelConnectionPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 终答直推必须带着绑定入站消息走被动回复；没有绑定信息时明确降级。 */
public class ChannelTerminalPushReplyTest {

    private static final String SESSION_ID = "qq-c2c-user-1";
    private static final String REQUEST_ID = "request-1";
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-12T02:00:00Z");

    @Test
    public void boundSessionUsesPassiveReply() throws Exception {
        CapturingAdapter adapter = new CapturingAdapter();
        ChannelTerminalPushService service = service(
                DialogueSession.builder()
                        .sessionId(SESSION_ID)
                        .bindingId(7L)
                        .channelChatType(ChannelChatType.C2C.name())
                        .channelTargetId("user-1")
                        .channelLastMsgId("inbound-msg-1")
                        .channelLastReceivedAt(LocalDateTime.ofInstant(RECEIVED_AT, ZoneOffset.UTC))
                        .build(),
                adapter);

        service.pushSuccess(SESSION_ID, REQUEST_ID, "终答");

        Assert.assertTrue(adapter.done.await(5, TimeUnit.SECONDS));
        Assert.assertEquals("inbound-msg-1", adapter.target.lastMsgId());
        Assert.assertEquals(RECEIVED_AT, adapter.target.receivedAt());
    }

    @Test
    public void missingBindingDegradesToActiveMessageWithReason() throws Exception {
        CapturingAdapter adapter = new CapturingAdapter();
        ChannelTerminalPushService service = service(
                DialogueSession.builder()
                        .sessionId(SESSION_ID)
                        .bindingId(7L)
                        .channelChatType(ChannelChatType.C2C.name())
                        .channelTargetId("user-1")
                        .build(),
                adapter);
        Logger logger = (Logger) LoggerFactory.getLogger(ChannelTerminalPushService.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);

        try {
            service.pushSuccess(SESSION_ID, REQUEST_ID, "终答");

            Assert.assertTrue(adapter.done.await(5, TimeUnit.SECONDS));
            Assert.assertNull(adapter.target.lastMsgId());
            Assert.assertTrue(warnings.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("no bound inbound msgId")
                            && message.contains(SESSION_ID)));
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }
    }

    @Test
    public void missingReceivedAtDegradesToActiveMessageWithReason() throws Exception {
        CapturingAdapter adapter = new CapturingAdapter();
        ChannelTerminalPushService service = service(
                DialogueSession.builder()
                        .sessionId(SESSION_ID)
                        .bindingId(7L)
                        .channelChatType(ChannelChatType.C2C.name())
                        .channelTargetId("user-1")
                        .channelLastMsgId("inbound-msg-1")
                        .build(),
                adapter);
        Logger logger = (Logger) LoggerFactory.getLogger(ChannelTerminalPushService.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);

        try {
            service.pushSuccess(SESSION_ID, REQUEST_ID, "终答");

            Assert.assertTrue(adapter.done.await(5, TimeUnit.SECONDS));
            Assert.assertNull(adapter.target.lastMsgId());
            Assert.assertTrue(warnings.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("reason=no bound inbound receivedAt")
                            && message.contains(SESSION_ID)));
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }
    }

    private static ChannelTerminalPushService service(DialogueSession session,
                                                      ChannelAdapter adapter) {
        IExecutionLedgerReadRepository ledger = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(ledger.querySessionEntity(SESSION_ID)).thenReturn(session);
        ChannelConnectionPort connectionPort = Mockito.mock(ChannelConnectionPort.class);
        Mockito.when(connectionPort.findAdapter(7L)).thenReturn(adapter);
        CuteConfig config = new CuteConfig();
        config.setChannelTerminalPush(true);

        ChannelTerminalPushService service = new ChannelTerminalPushService();
        ReflectionTestUtils.setField(service, "ledgerReadRepository", ledger);
        ReflectionTestUtils.setField(service, "cuteConfig", config);
        ReflectionTestUtils.setField(service, "channelConnectionPortProvider",
                provider(connectionPort));
        ReflectionTestUtils.setField(service, "channelArtifactResolverProvider",
                provider(Mockito.mock(ChannelArtifactResolver.class)));
        return service;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = Mockito.mock(ObjectProvider.class);
        Mockito.when(result.getIfAvailable()).thenReturn(value);
        return result;
    }

    private static final class CapturingAdapter implements ChannelAdapter {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile ChannelTarget target;

        @Override
        public String platform() {
            return "qqbot";
        }

        @Override
        public ChannelPushResult doPushText(ChannelTarget target, String text) {
            this.target = target;
            done.countDown();
            return ChannelPushResult.ok("outbound-msg-1");
        }
    }
}
