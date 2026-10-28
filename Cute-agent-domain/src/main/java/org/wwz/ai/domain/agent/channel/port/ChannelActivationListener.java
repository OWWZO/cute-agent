package org.wwz.ai.domain.agent.channel.port;

/**
 * 绑定成功后的回调：起连接。
 * <p>由连接管理（infrastructure）实现，case 层的扫码状态机在 COMPLETED 时调用——
 * 起连接是扫码完成的最后一步，漏掉它 binding 会永远停在 PENDING。</p>
 */
public interface ChannelActivationListener {

    void onActivated(Long bindingId, String ownerUserId);
}
