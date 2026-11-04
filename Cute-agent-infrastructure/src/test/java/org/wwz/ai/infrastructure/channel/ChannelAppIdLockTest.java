package org.wwz.ai.infrastructure.channel;

import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelAppIdLockTest {

    @Test
    public void overlappingLockReturnsFalseInsteadOfThrowing() throws Exception {
        String lockDir = Files.createTempDirectory("channel-app-lock").toString();
        ChannelAppIdLock first = new ChannelAppIdLock(lockDir);
        ChannelAppIdLock second = new ChannelAppIdLock(lockDir);
        try {
            assertTrue(first.tryLock("app-1"));
            assertFalse(second.tryLock("app-1"));
        } finally {
            second.unlock("app-1");
            first.unlock("app-1");
        }
    }
}
