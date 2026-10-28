package org.wwz.ai.infrastructure.channel.qqbot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class QqBotMentionStripTest {

    @Test
    public void stripsPlainAtPrefix() {
        assertEquals("你好", QqBotWsClient.stripAtMention("@机器人 你好"));
    }

    @Test
    public void stripsAngleBracketMention() {
        assertEquals("帮我查天气", QqBotWsClient.stripAtMention("<@!123456> 帮我查天气"));
    }

    @Test
    public void keepsContentWithoutMention() {
        // Phase 0 若确认 content 不含前缀，这条路径就是常态。
        assertEquals("你好", QqBotWsClient.stripAtMention("你好"));
    }

    @Test
    public void keepsInnerAtSign() {
        assertEquals("发邮件给 a@b.com", QqBotWsClient.stripAtMention("发邮件给 a@b.com"));
    }

    @Test
    public void blankContentBecomesEmpty() {
        assertEquals("", QqBotWsClient.stripAtMention("   "));
        assertEquals("", QqBotWsClient.stripAtMention(null));
    }
}
