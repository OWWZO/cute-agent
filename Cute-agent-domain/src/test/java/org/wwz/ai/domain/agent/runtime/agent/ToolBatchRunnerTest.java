package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 join 失败回归：无论失败原因是不是超时，都必须取消同批未完成的执行 future；
 * 工具自身抛出的 TimeoutException 不能被误当成批次截止时间。
 */
public class ToolBatchRunnerTest {

    @Test
    public void joinFailureCancelsUnfinishedSiblingExecutions() {
        AgentContext context = AgentContext.builder().requestId("req-join").build();
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.getContext()).thenReturn(context);
        ToolBatchRunner runner = new ToolBatchRunner(agent);

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("tool handler exploded"));
        CompletableFuture<Object> pending = new CompletableFuture<>();

        runner.await(List.of(failed), List.of(pending), List.of());

        Assert.assertTrue("join 失败必须终止同批其它工具，避免后台继续产生副作用",
                pending.isCancelled());
    }

    @Test
    public void toolTimeoutDoesNotAbortBatchAsBatchDeadline() {
        AgentContext context = AgentContext.builder().requestId("req-tool-timeout").build();
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.getContext()).thenReturn(context);
        ToolBatchRunner runner = new ToolBatchRunner(agent);

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("upstream http timeout"));
        CompletableFuture<Object> pending = new CompletableFuture<>();

        runner.await(List.of(failed), List.of(pending), List.of());

        Assert.assertTrue("工具自身超时同样要终止未完成兄弟任务", pending.isCancelled());
    }

    @Test
    public void agentDispatchIsAlwaysTreatedAsLongRunning() {
        ToolCall call = new ToolCall();
        ToolCall.Function function = new ToolCall.Function();
        function.setName("Agent");
        call.setFunction(function);

        Assert.assertTrue(ToolBatchRunner.containsLongRunningTool(List.of(call)));
        Assert.assertFalse(ToolBatchRunner.containsLongRunningTool(List.of()));
    }
}
