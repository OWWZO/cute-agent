package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.policy.ChannelUserId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ChannelUserIdTest {

    @Test
    public void isDeterministicForSameInput() {
        String a = ChannelUserId.of(42L, "A1B2C3");
        String b = ChannelUserId.of(42L, "A1B2C3");
        assertEquals(a, b);
        assertEquals("ch_42_", a.substring(0, 6));
        assertEquals(22, a.length());
    }

    @Test
    public void sameUserOnDifferentBindingsGetsDifferentIdentity() {
        // openid 按 bot 隔离，身份也必须按 bot 隔离。
        assertNotEquals(ChannelUserId.of(1L, "openid"), ChannelUserId.of(2L, "openid"));
    }

    @Test
    public void differentUsersOnSameBindingDiffer() {
        assertNotEquals(ChannelUserId.of(1L, "openid-a"), ChannelUserId.of(1L, "openid-b"));
    }

    @Test
    public void hashIsHexAndFitsColumn() {
        String userId = ChannelUserId.of(999999L, "some-openid");
        assertTrue(userId.matches("ch_\\d+_[0-9a-f]{16}"));
        assertTrue(userId.length() <= 64);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullBindingIsRejected() {
        ChannelUserId.of(null, "openid");
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankPlatformUserIsRejected() {
        ChannelUserId.of(1L, "  ");
    }
}
