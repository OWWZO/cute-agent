package org.wwz.ai.application.agent.channel.outbound;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.policy.ChannelFrameFilter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一轮 run 的帧流收成「一次 IM 出站」。
 * <p>挂到 {@code AgentStreamProjection.addDownstream} 后，SSE 观察者与它并列接收帧；
 * 它只挑终答那一帧发出去，其余丢弃。</p>
 */
@Slf4j
public class ChannelFrameAggregator {

    /** 出站动作，由 case 层注入（需要产物解析，不便放进 domain）。 */
    @FunctionalInterface
    public interface Deliverer {
        ChannelPushResult deliver(ChannelTarget target, String text);
    }

    private final String requestId;
    private final ChannelTarget target;
    private final Deliverer deliverer;

    /** 同一 requestId 的终帧可能被投递多次（缓冲补发 + live 推送），只发第一次。 */
    private final AtomicBoolean delivered = new AtomicBoolean(false);

    public ChannelFrameAggregator(String requestId, ChannelTarget target, Deliverer deliverer) {
        this.requestId = requestId;
        this.target = target;
        this.deliverer = deliverer;
    }

    public void onFrame(String packageType, boolean finished, String responseAll) {
        if (!ChannelFrameFilter.shouldDeliver(packageType, finished, responseAll)) {
            return;
        }
        if (!delivered.compareAndSet(false, true)) {
            return;
        }
        try {
            ChannelPushResult result = deliverer.deliver(target, responseAll);
            if (result != null && !result.success()) {
                log.warn("IM push failed requestId={} error={}", requestId, result.error());
            }
        } catch (Exception e) {
            // 出站失败不能冒泡到 projection 的观察者循环，否则会影响 SSE 主链路。
            log.warn("IM push threw requestId={}", requestId, e);
        }
    }

    /** run 失败也要告知用户，静默失败会让用户以为机器人挂了。 */
    public void onError(Throwable throwable) {
        if (!delivered.compareAndSet(false, true)) {
            return;
        }
        String message = throwable == null || throwable.getMessage() == null
                ? "处理失败"
                : "处理失败：" + throwable.getMessage();
        try {
            deliverer.deliver(target, message);
        } catch (Exception e) {
            log.warn("IM error push threw requestId={}", requestId, e);
        }
    }

    public boolean isDelivered() {
        return delivered.get();
    }
}
