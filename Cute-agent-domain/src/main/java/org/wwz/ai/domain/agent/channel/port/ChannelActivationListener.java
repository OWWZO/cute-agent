package org.wwz.ai.domain.agent.channel.port;

/**
 * 绑定成功后的回调：起连接。
 * <p>由连接管理（infrastructure）实现，case 层的扫码状态机在 COMPLETED 时调用——
 * 起连接是扫码完成的最后一步，漏掉它 binding 会永远停在 PENDING。</p>
 *
 * <p><b>调用上下文（调用方保证）：</b></p>
 * <ul>
 *   <li>只在绑定已经落库、状态机已置 COMPLETED 之后调用，不在调用方事务内；</li>
 *   <li>在扫码轮询调度线程（{@code channel-qr-poller}）上同步调用，不在 HTTP 请求线程；</li>
 *   <li>本方法抛出的异常会被调用方捕获并记录日志，不会回滚 COMPLETED 状态，也不会重试——
 *       实现应自行处理失败并回写 binding 状态（例如转 ERROR），不要依赖调用方感知。</li>
 * </ul>
 *
 * <p><b>幂等：</b>正常路径每次 COMPLETED 只回调一次，但实现必须容忍重复调用（例如重复的
 * COMPLETED 事件或重试）；重复调用不得为同一 binding 建立第二条连接。</p>
 */
public interface ChannelActivationListener {

    /**
     * @param bindingId   已完成绑定的主键，调用方保证非 null
     * @param ownerUserId 任务发起者，调用方保证非 null；当前实现不参与建连，仅供实现做归属校验或日志
     */
    void onActivated(Long bindingId, String ownerUserId);
}
