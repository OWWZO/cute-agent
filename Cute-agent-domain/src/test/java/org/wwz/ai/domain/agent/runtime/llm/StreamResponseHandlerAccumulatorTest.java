package org.wwz.ai.domain.agent.runtime.llm;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式增量拼接：id-less delta 必须并回首个 delta 的累加器；前缀型合法 delta 不能被丢弃。
 */
public class StreamResponseHandlerAccumulatorTest {

    @Test
    public void idLessDeltasMergeIntoFirstDeltaAccumulator() {
        Map<String, StreamResponseHandler.ToolCallAccumulator> accumulators = new LinkedHashMap<>();
        StreamResponseHandler.mergeToolCalls(List.of(
                LlmToolCall.builder().id("call_a").name("write").arguments("{\"path\":").build(),
                LlmToolCall.builder().id("call_b").name("write").arguments("{\"path\":").build()
        ), accumulators);
        StreamResponseHandler.mergeToolCalls(List.of(
                LlmToolCall.builder().arguments("\"a.txt\"}").build(),
                LlmToolCall.builder().arguments("\"b.txt\"}").build()
        ), accumulators);

        Assert.assertEquals("id-less delta 不能新建无 name 的累加器", 2, accumulators.size());
        Assert.assertEquals("call_a", accumulators.get("idx#0").toToolCall().getId());
        Assert.assertEquals("write", accumulators.get("idx#0").toToolCall().getFunction().getName());
        Assert.assertEquals("{\"path\":\"a.txt\"}", accumulators.get("idx#0").toToolCall().getFunction().getArguments());
        Assert.assertEquals("{\"path\":\"b.txt\"}", accumulators.get("idx#1").toToolCall().getFunction().getArguments());
    }

    @Test
    public void delayedIdDoesNotDropAccumulatedFields() {
        Map<String, StreamResponseHandler.ToolCallAccumulator> accumulators = new LinkedHashMap<>();
        StreamResponseHandler.mergeToolCalls(List.of(
                LlmToolCall.builder().name("write").arguments("{\"a\":").build()
        ), accumulators);
        StreamResponseHandler.mergeToolCalls(List.of(
                LlmToolCall.builder().id("call_x").arguments("1}").build()
        ), accumulators);

        Assert.assertEquals(1, accumulators.size());
        Assert.assertEquals("write", accumulators.get("idx#0").toToolCall().getFunction().getName());
        Assert.assertEquals("{\"a\":1}", accumulators.get("idx#0").toToolCall().getFunction().getArguments());
        Assert.assertEquals("call_x", accumulators.get("idx#0").toToolCall().getId());
    }

    @Test
    public void prefixLikeDeltaIsAppendedForToolArguments() {
        StreamResponseHandler.ToolCallAccumulator accumulator = new StreamResponseHandler.ToolCallAccumulator();
        accumulator.merge(LlmToolCall.builder().name("write").arguments("{\"a\":").build());
        accumulator.merge(LlmToolCall.builder().arguments("{\"").build());

        Assert.assertEquals("{\"a\":{\"", accumulator.toToolCall().getFunction().getArguments());
    }

    @Test
    public void prefixLikeDeltaIsAppendedForReasoning() {
        StringBuilder all = new StringBuilder("abc");

        String delta = StreamResponseHandler.appendReasoningChunk(all, "ab");

        Assert.assertEquals("ab", delta);
        Assert.assertEquals("abcab", all.toString());
    }

    @Test
    public void cumulativeReasoningSnapshotStillReplaces() {
        StringBuilder all = new StringBuilder("abc");

        String delta = StreamResponseHandler.appendReasoningChunk(all, "abcd");

        Assert.assertEquals("d", delta);
        Assert.assertEquals("abcd", all.toString());
    }
}
