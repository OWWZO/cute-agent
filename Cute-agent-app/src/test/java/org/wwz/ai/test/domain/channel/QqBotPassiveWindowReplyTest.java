package org.wwz.ai.test.domain.channel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotAdapter;
import org.wwz.ai.types.agent.config.ChannelProperties;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** 超过配置的被动窗口时，QQ 适配器按既有规则去掉 msg_id 并说明原因。 */
public class QqBotPassiveWindowReplyTest {

    @Test
    public void expiredReplyFallsBackToActiveMessageWithReason() {
        ChannelProperties properties = new ChannelProperties();
        properties.getQqbot().setReplyWindowSeconds(60);
        AtomicReference<ChannelTarget> sentTarget = new AtomicReference<>();
        QqBotAdapter adapter = new QqBotAdapter(null, properties) {
            @Override
            protected ChannelPushResult sendOne(ChannelTarget target, String chunk) {
                sentTarget.set(target);
                return ChannelPushResult.ok("outbound-1");
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(QqBotAdapter.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);

        try {
            adapter.pushText(new ChannelTarget(
                    QqBotAdapter.PLATFORM, 1L, ChannelChatType.C2C, "user-1",
                    "inbound-1", Instant.now().minusSeconds(61)), "终答");

            Assert.assertNull(sentTarget.get().lastMsgId());
            Assert.assertTrue(warnings.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("degrading to active message")
                            && message.contains("ttl=60s")));
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }
    }
}
