package org.wwz.ai.domain.agent.adapter.port;

/**
 * 远端流式会话句柄。
 * 用于在 domain 侧超时或降级时主动取消长连接。
 */
public interface RemoteStreamSession {

    /**
     * 主动取消流式请求。
     *
     * <p>契约：</p>
     * <ul>
     *   <li>幂等——重复调用是无副作用的空操作；</li>
     *   <li>线程安全——允许从不同线程并发调用；</li>
     *   <li>非阻塞——不得等待传输层关闭（当前实现直接委托 OkHttp {@code Call.cancel()}）；</li>
     *   <li>本方法不抛异常；取消后在飞的读取可能以
     *       {@link RemoteStreamListener#onFailure} 结束，调用方必须容忍取消后仍收到一次失败回调。</li>
     * </ul>
     */
    void cancel();
}
