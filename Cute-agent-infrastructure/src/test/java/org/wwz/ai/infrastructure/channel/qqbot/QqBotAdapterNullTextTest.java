package org.wwz.ai.infrastructure.channel.qqbot;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.types.agent.config.ChannelProperties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class QqBotAdapterNullTextTest {

    @Test
    public void nullTextReturnsFailedResultBeforeMarkdownConversion() {
        QqBotAdapter adapter = new QqBotAdapter(null, new ChannelProperties());
        ChannelTarget target = new ChannelTarget(
                QqBotAdapter.PLATFORM, 1L, ChannelChatType.C2C, "user-1", null, null);

        ChannelPushResult result = adapter.doPushText(target, null);

        assertFalse(result.success());
        assertEquals("正文为空", result.error());
    }
}
