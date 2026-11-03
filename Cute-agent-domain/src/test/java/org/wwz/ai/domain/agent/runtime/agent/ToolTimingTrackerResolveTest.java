package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

/**
 * resolve 必须返回 computeIfAbsent 的结果，不能先插入再二次读取。
 */
public class ToolTimingTrackerResolveTest {

    @Test
    public void resolveShouldAlwaysReturnNonNullForValidToolCallId() {
        ToolTimingTracker tracker = new ToolTimingTracker();
        ToolCall command = toolCall("call-1");

        Assert.assertNotNull(tracker.resolve(command));
        Assert.assertSame(tracker.resolve(command), tracker.resolve(command));

        tracker.clear(command);
        Assert.assertNotNull("clear 之后 resolve 必须重新补建", tracker.resolve(command));
    }

    @Test
    public void resolveShouldReturnNullForBlankToolCallId() {
        ToolTimingTracker tracker = new ToolTimingTracker();

        Assert.assertNull(tracker.resolve(null));
        Assert.assertNull(tracker.resolve(toolCall(" ")));
    }

    private ToolCall toolCall(String id) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder().name("tool").arguments("{}").build())
                .build();
    }
}
