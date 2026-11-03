package org.wwz.ai.application.agent.channel.outbound;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;

/**
 * 挂在 {@code AgentStreamProjection.addDownstream} 上的观察者。
 * <p>把投影帧解出三要素交给 {@link ChannelFrameAggregator}，其余帧一律忽略。</p>
 */
@Slf4j
public class ChannelObserverStream implements AgentSessionStream {

    private final ChannelFrameAggregator aggregator;

    public ChannelObserverStream(ChannelFrameAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void send(Object payload) {
        if (!(payload instanceof AgentSessionStreamFrame frame)) {
            return;
        }
        aggregator.onFrame(frame.getPackageType(), frame.isFinished(), frame.getResponseAll());
    }

    @Override
    public void complete() {
        // 出站已在终帧时完成，这里无需动作。
    }

    @Override
    public void completeWithError(Throwable throwable) {
        aggregator.onError(throwable);
    }

    /**
     * 必须恒为 false。
     * <p>{@code AgentStreamProjection} 用 {@code !stream.isAborted()} 过滤观察者，
     * 返回 true 会被静默摘除，导致回复丢失。IM 没有「下游断开」的概念——REST 发送是无状态的。</p>
     */
    @Override
    public boolean isAborted() {
        return false;
    }

    /** IM 观察者没有下游连接可断开；显式声明空操作，避免依赖端口默认实现。 */
    @Override
    public void onAbort(Runnable abortHandler) {
    }
}
