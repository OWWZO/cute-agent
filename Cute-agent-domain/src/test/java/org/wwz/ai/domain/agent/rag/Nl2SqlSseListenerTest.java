package org.wwz.ai.domain.agent.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.AgentMessageStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * NL2SQL SSE 监听器：onLine 异常时必须自行释放等待方，等待本身必须有上界。
 */
public class Nl2SqlSseListenerTest {

    @Test
    public void onLineFailureReleasesWaiterAndMarksFailure() throws Exception {
        AgentMessageStream stream = mock(AgentMessageStream.class);
        doThrow(new RuntimeException("send failed")).when(stream).send(any());
        Nl2SqlQueryService.Nl2SqlSseListener listener =
                new Nl2SqlQueryService.Nl2SqlSseListener(stream, "req-1", "trace-1");

        try {
            listener.onLine("data:{\"status\":\"nl2sql_think\",\"nl2sqlThink\":\"思考中\"}");
            Assert.fail("onLine 解析/发送失败应向上抛出");
        } catch (RuntimeException expected) {
            // 预期
        }

        Assert.assertEquals("等待方必须被释放", 0L, listener.getCountDownLatch().getCount());
        Assert.assertFalse(listener.isSuccess());
        Assert.assertNotNull(listener.getErrorMessage());
    }

    @Test
    public void awaitStreamIsBoundedWhenNoTerminalEventArrives() throws Exception {
        Nl2SqlQueryService.Nl2SqlSseListener listener =
                new Nl2SqlQueryService.Nl2SqlSseListener(mock(AgentMessageStream.class), "req-2", "trace-2");

        long start = System.currentTimeMillis();
        boolean signalled = Nl2SqlQueryService.awaitStream(listener, 0L);

        Assert.assertFalse("无终态事件时等待必须有界返回", signalled);
        Assert.assertTrue("等待上界不得无限阻塞", System.currentTimeMillis() - start < 5000L);
    }
}
