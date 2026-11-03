package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.printer.Printer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具收口并发回归：handler 回写与超时/取消兜底同时到达时，只能有一个线程 settle。
 */
public class ToolExecutionPipelineCompletionTest {

    @Test
    public void concurrentCompletionSettlesOnlyOncePerToolCall() throws Exception {
        AgentContext context = AgentContext.builder().requestId("req-complete").build();
        Printer printer = mock(Printer.class);
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.getContext()).thenReturn(context);
        when(agent.getPrinter()).thenReturn(printer);
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(agent);

        ToolCall call = ToolCall.builder()
                .id("call-1")
                .type("function")
                .function(ToolCall.Function.builder().name("demo_tool").arguments("{}").build())
                .build();
        BarrierMap<String, ToolExecutionOutcome> result =
                new BarrierMap<>(new CyclicBarrier(2));
        ToolExecutionOutcome outcome = ToolExecutionOutcome.success("ok", "ok", null, null, null);

        Thread first = new Thread(() -> pipeline.completeToolOutcome(
                result, call, Map.of(), outcome, Map.of(), true));
        Thread second = new Thread(() -> pipeline.completeToolOutcome(
                result, call, Map.of(), outcome, Map.of(), true));
        first.start();
        second.start();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        Assert.assertFalse(first.isAlive());
        Assert.assertFalse(second.isAlive());
        Assert.assertEquals(1, result.size());
        verify(printer, times(1)).send(anyString(), eq("tool_call"), any(), eq(true));
    }

    /**
     * containsKey 里放一个双人栅栏：旧实现（containsKey + put）会让两个线程同时通过检查，
     * 修复后的 putIfAbsent 不再调用 containsKey。
     */
    private static final class BarrierMap<K, V> extends ConcurrentHashMap<K, V> {
        private final transient CyclicBarrier barrier;

        private BarrierMap(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public boolean containsKey(Object key) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return super.containsKey(key);
        }
    }
}
