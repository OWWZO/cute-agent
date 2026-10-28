package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelSessionKeyTest {

    private static final String OPENID = "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4";
    private static final String GROUP_OPENID = "B1C2D3E4F5A6B1C2D3E4F5A6B1C2D3E4";

    @Test
    public void c2cKeyIsStableAndPrefixed() {
        String key = ChannelSessionKey.of(ChannelChatType.C2C, OPENID, OPENID);
        assertEquals("qq_c2c_" + OPENID, key);
        assertEquals(key, ChannelSessionKey.of(ChannelChatType.C2C, OPENID, OPENID));
    }

    @Test
    public void groupKeyIncludesBothGroupAndMember() {
        String key = ChannelSessionKey.of(ChannelChatType.GROUP, GROUP_OPENID, OPENID);
        assertEquals("qq_group_" + GROUP_OPENID + "_" + OPENID, key);
    }

    @Test
    public void differentMembersInSameGroupGetDifferentSessions() {
        String a = ChannelSessionKey.of(ChannelChatType.GROUP, GROUP_OPENID, "member-a");
        String b = ChannelSessionKey.of(ChannelChatType.GROUP, GROUP_OPENID, "member-b");
        assertNotEquals(a, b);
    }

    @Test
    public void c2cAndGroupNeverCollide() {
        String c2c = ChannelSessionKey.of(ChannelChatType.C2C, OPENID, OPENID);
        String group = ChannelSessionKey.of(ChannelChatType.GROUP, OPENID, OPENID);
        assertNotEquals(c2c, group);
    }

    @Test
    public void guildAndDmUseSingleTarget() {
        assertEquals("qq_guild_ch1", ChannelSessionKey.of(ChannelChatType.GUILD, "ch1", null));
        assertEquals("qq_dm_g1", ChannelSessionKey.of(ChannelChatType.DM, "g1", null));
    }

    @Test
    public void longestRealisticKeyFitsColumnWidth() {
        // QQ openid 最长 32 位，group_openid 同量级；74 是设计时算出的上界。
        String key = ChannelSessionKey.of(ChannelChatType.GROUP, "g".repeat(32), "u".repeat(32));
        assertEquals(74, key.length());
        assertTrue(key.length() <= ChannelSessionKey.MAX_LENGTH);
    }

    @Test
    public void blankIdentifiersAreRejected() {
        assertThrows(() -> ChannelSessionKey.of(ChannelChatType.C2C, " ", "u"));
        assertThrows(() -> ChannelSessionKey.of(ChannelChatType.GROUP, "g", null));
        assertThrows(() -> ChannelSessionKey.of(null, "g", "u"));
    }

    @Test
    public void oversizedIdentifierIsRejectedInsteadOfSilentlyTruncated() {
        assertThrows(() -> ChannelSessionKey.of(ChannelChatType.C2C, "x".repeat(200), "u"));
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
            fail("应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
