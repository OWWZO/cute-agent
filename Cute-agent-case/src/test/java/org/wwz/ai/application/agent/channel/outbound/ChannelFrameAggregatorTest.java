package org.wwz.ai.application.agent.channel.outbound;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.application.agent.channel.outbound.ChannelFrameAggregator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelFrameAggregatorTest {

    private static final ChannelTarget TARGET = new ChannelTarget(
            "qqbot", 1L, ChannelChatType.C2C, "openid-1", "msg-1", Instant.now());

    @Test
    public void deliversFinishedResultOnce() {
        List<String> sent = new ArrayList<>();
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                "req-1", TARGET, (target, text) -> {
                    sent.add(text);
                    return ChannelPushResult.ok("m1");
                });

        aggregator.onFrame("result", true, "你好");
        // 缓冲补发会重复投递同一终帧，必须幂等。
        aggregator.onFrame("result", true, "你好");

        assertEquals(List.of("你好"), sent);
        assertTrue(aggregator.isDelivered());
    }

    @Test
    public void ignoresIntermediateFrames() {
        List<String> sent = new ArrayList<>();
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                "req-2", TARGET, (target, text) -> {
                    sent.add(text);
                    return ChannelPushResult.ok("m1");
                });

        aggregator.onFrame("agent_stream", false, "半句话");
        aggregator.onFrame("tool", true, "");
        aggregator.onFrame("heartbeat", false, "");
        aggregator.onFrame("result", false, "还没完");
        aggregator.onFrame("result", true, "");

        assertTrue(sent.isEmpty());
        assertFalse(aggregator.isDelivered());
    }

    @Test
    public void errorIsReportedToUser() {
        List<String> sent = new ArrayList<>();
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                "req-3", TARGET, (target, text) -> {
                    sent.add(text);
                    return ChannelPushResult.ok("m1");
                });

        aggregator.onError(new IllegalStateException("模型超时"));

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("模型超时"));
    }

    @Test
    public void errorAfterSuccessDoesNotSendTwice() {
        List<String> sent = new ArrayList<>();
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                "req-4", TARGET, (target, text) -> {
                    sent.add(text);
                    return ChannelPushResult.ok("m1");
                });

        aggregator.onFrame("result", true, "成功答案");
        aggregator.onError(new IllegalStateException("收尾报错"));

        assertEquals(List.of("成功答案"), sent);
    }

    @Test
    public void deliveryFailureIsSwallowed() {
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                "req-5", TARGET, (target, text) -> {
                    throw new IllegalStateException("网络炸了");
                });

        // 出站异常不能冒泡到 projection 的观察者循环。
        aggregator.onFrame("result", true, "正文");
        assertTrue(aggregator.isDelivered());
    }
}
