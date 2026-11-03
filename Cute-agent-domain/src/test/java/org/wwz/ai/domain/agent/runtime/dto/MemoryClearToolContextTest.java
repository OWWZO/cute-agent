package org.wwz.ai.domain.agent.runtime.dto;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;

import java.util.List;

/**
 * clearToolContext 同一元素命中多个条件时只能 remove 一次；null 元素也不能 NPE。
 */
public class MemoryClearToolContextTest {

    private static final String LEGACY_PREFIX = "根据当前状态和可用工具，确定下一步行动";

    @Test
    public void toolMessageMatchingLegacyPrefixShouldBeRemovedOnce() {
        Memory memory = new Memory();
        memory.addMessage(Message.builder()
                .role(RoleType.TOOL)
                .content(LEGACY_PREFIX + "：继续")
                .build());

        memory.clearToolContext();

        Assert.assertTrue(memory.isEmpty());
    }

    @Test
    public void assistantWithToolCallsMatchingLegacyPrefixShouldBeRemovedOnce() {
        Memory memory = new Memory();
        memory.addMessage(Message.builder()
                .role(RoleType.ASSISTANT)
                .content(LEGACY_PREFIX + "：调用工具")
                .toolCalls(List.of(ToolCall.builder().id("call-1").build()))
                .build());

        memory.clearToolContext();

        Assert.assertTrue(memory.isEmpty());
    }

    @Test
    public void nullMessageShouldNotBreakClearing() {
        Memory memory = new Memory();
        memory.addMessage(null);
        memory.addMessage(Message.builder().role(RoleType.USER).content("保留").build());

        memory.clearToolContext();

        Assert.assertEquals(1, memory.size());
        Assert.assertEquals("保留", memory.get(0).getContent());
    }
}
