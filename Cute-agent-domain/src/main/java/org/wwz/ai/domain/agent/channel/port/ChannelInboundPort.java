package org.wwz.ai.domain.agent.channel.port;

import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.model.ChannelInboundEvent;

/**
 * 入站分发端口：WS 连接层（infrastructure）收到平台消息后，经它交给应用编排（case）。
 * <p>定义在 domain 是因为 infrastructure 不能反向依赖 case。</p>
 */
public interface ChannelInboundPort {

    void dispatch(ChannelInboundEvent event, ChannelBinding binding, ChannelAdapter adapter);
}
