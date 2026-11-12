package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/** 会话 mapper 必须把最近入站消息映射、读取并写回，避免终答丢掉 msg_id。 */
public class DialogueSessionReplyMapperTest {

    @Test
    public void mapperCarriesReplyContext() throws Exception {
        String xml = new ClassPathResource("mybatis/mapper/dialogue_session_ledger_mapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        Assert.assertTrue(xml.contains(
                "<result column=\"channel_last_msg_id\" property=\"channelLastMsgId\"/>"));
        Assert.assertTrue(xml.contains(
                "<result column=\"channel_last_received_at\" property=\"channelLastReceivedAt\"/>"));
        Assert.assertTrue(xml.contains(
                "channel_last_msg_id = VALUES(channel_last_msg_id)"));
        Assert.assertTrue(xml.contains(
                "channel_last_received_at = VALUES(channel_last_received_at)"));
        Assert.assertTrue(xml.contains(
                "channel_last_msg_id = COALESCE(VALUES(channel_last_msg_id), channel_last_msg_id)"));
        Assert.assertTrue(xml.contains(
                "channel_last_received_at = COALESCE(VALUES(channel_last_received_at), channel_last_received_at)"));
    }
}
