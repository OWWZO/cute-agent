package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelFrameFilterTest {

    @Test
    public void deliversOnlyFinishedResultWithBody() {
        assertTrue(ChannelFrameFilter.shouldDeliver("result", true, "你好"));
    }

    @Test
    public void rejectsUnfinishedResult() {
        // 流式过程中的 result 帧正文还没攒齐，投递会只发半句话。
        assertFalse(ChannelFrameFilter.shouldDeliver("result", false, "你好"));
    }

    @Test
    public void rejectsBlankBody() {
        assertFalse(ChannelFrameFilter.shouldDeliver("result", true, ""));
        assertFalse(ChannelFrameFilter.shouldDeliver("result", true, "   "));
        assertFalse(ChannelFrameFilter.shouldDeliver("result", true, null));
    }

    @Test
    public void rejectsEveryOtherPackageType() {
        String[] others = {
                "heartbeat", "follow_idle", "follow_pending",
                "tool", "tool_result", "plan", "agent_stream", "stream_settle"
        };
        for (String packageType : others) {
            assertFalse(packageType, ChannelFrameFilter.shouldDeliver(packageType, true, "正文"));
        }
    }

    @Test
    public void rejectsNullPackageType() {
        assertFalse(ChannelFrameFilter.shouldDeliver(null, true, "正文"));
    }
}
