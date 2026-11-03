package org.wwz.ai.domain.agent.channel.port;

import org.wwz.ai.domain.agent.channel.model.ChannelBinding;

/**
 * 连接生命周期端口。
 * <p>定义在 domain 层，实现放在 infrastructure 层——case 不该知道 OkHttp / WS 的存在。</p>
 */
public interface ChannelConnectionPort {

    /**
     * 异步建立连接。不阻塞调用方，不抛异常：结果通过 binding 状态回写体现。
     */
    void startConnection(ChannelBinding binding);

    /** 优雅关闭（带超时）。幂等。 */
    void stopConnection(Long bindingId);

    /**
     * 该绑定当前是否有活连接（点位快照，仅作提示）。
     * <p>返回值不能作为发送前的授权依据：查询与发送之间连接可能断开。
     * 调用方应先发送，再按发送结果处理连接丢失/重连，不要写成
     * {@code if (isConnected(id)) send(...)} 的 check-then-act。</p>
     */
    boolean isConnected(Long bindingId);
}
