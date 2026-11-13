package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.CompactionPrompt;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactor;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;

import java.util.List;

public class MessageCopyOfTest {

    private final WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();

    @Test
    public void copyOfPreservesEveryMessageField() {
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .type("function")
                .function(ToolCall.Function.builder().name("search").arguments("{}").build())
                .build();
        Message source = Message.builder()
                .role(RoleType.ASSISTANT)
                .originMessageKey("origin-1")
                .modelOnly(true)
                .content("answer")
                .reasoningContent("reasoning")
                .base64Image("data:image/png;base64,AAA")
                .toolCallId("result-1")
                .toolCalls(List.of(toolCall))
                .build();

        Message copy = Message.copyOf(source).build();

        Assert.assertEquals(source.getRole(), copy.getRole());
        Assert.assertEquals(source.getOriginMessageKey(), copy.getOriginMessageKey());
        Assert.assertEquals(source.getModelOnly(), copy.getModelOnly());
        Assert.assertEquals(source.getContent(), copy.getContent());
        Assert.assertEquals(source.getReasoningContent(), copy.getReasoningContent());
        Assert.assertEquals(source.getBase64Image(), copy.getBase64Image());
        Assert.assertEquals(source.getToolCallId(), copy.getToolCallId());
        Assert.assertSame(source.getToolCalls(), copy.getToolCalls());
    }

    @Test
    public void mergeHandoffIntoTailPreservesModelOnlyAndReasoning() {
        String wrapped = CompactionPrompt.wrapSummaryForReinject("## Goal\ncontinue", true);
        Message tail = Message.builder()
                .role(RoleType.USER)
                .originMessageKey("origin-tail")
                .modelOnly(true)
                .content("后台通知")
                .reasoningContent("模型推理")
                .base64Image("data:image/png;base64,BBB")
                .toolCallId("unexpected-result")
                .toolCalls(List.of(ToolCall.builder().id("call-tail").build()))
                .build();

        Message merged = compactor.mergeHandoffIntoTail(List.of(tail), wrapped).get(0);

        Assert.assertTrue(Boolean.TRUE.equals(merged.getModelOnly()));
        Assert.assertEquals("模型推理", merged.getReasoningContent());
        Assert.assertEquals("origin-tail", merged.getOriginMessageKey());
        Assert.assertEquals("data:image/png;base64,BBB", merged.getBase64Image());
        Assert.assertEquals("unexpected-result", merged.getToolCallId());
        Assert.assertSame(tail.getToolCalls(), merged.getToolCalls());
        Assert.assertTrue(merged.getContent().contains("后台通知"));
    }
}
