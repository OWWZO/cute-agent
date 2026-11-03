package org.wwz.ai.domain.agent.channel.port;

import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.model.ChannelInboundEvent;

/**
 * 入站分发端口：WS 连接层（infrastructure）收到平台消息后，经它交给应用编排（case）。
 * <p>定义在 domain 是因为 infrastructure 不能反向依赖 case。</p>
 * <p><b>线程与顺序：</b>由各绑定的 WS 读线程调用，不同绑定之间可能并发，同一绑定内按连接内
 * 到达顺序串行；实现必须是线程安全的，端口不承诺跨绑定的顺序。</p>
 */
public interface ChannelInboundPort {

    /**
     * 分发一条已归一化的入站消息。
     * <p><b>失败语义：</b>方法返回 void 且不向调用方抛出异常——实现必须自行消化下游失败
     * （当前实现捕获异常并尽力回推失败提示），WS 层不会收到失败信号，也没有 ack/重试。
     * 因此实现不能在失败时静默丢弃，至少要记录日志或回执用户。</p>
     *
     * @param event   入站事件；为 null 时实现直接返回
     * @param binding 消息所属绑定；为 null 时实现直接返回
     * @param adapter 该绑定的出站适配器；为 null 时实现直接返回
     */
    void dispatch(ChannelInboundEvent event, ChannelBinding binding, ChannelAdapter adapter);
}
