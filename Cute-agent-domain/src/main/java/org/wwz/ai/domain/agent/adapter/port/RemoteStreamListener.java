package org.wwz.ai.domain.agent.adapter.port;

/**
 * 协议无关的远端流式监听器。
 * infrastructure 负责把底层 SSE/HTTP 分帧结果转成按行事件回调给 domain。
 * <p>线程与顺序契约：同一次 {@code openStream} 的所有回调都在同一线程上串行触发，
 * 不会并发进入；{@link #onOpen()} 一定先于 {@link #onLine(String)}；
 * {@link #onClosed()} 与 {@link #onFailure(Throwable, Integer, String)} 互斥，
 * 一次流只会收到其中一个，且之后不会再有回调。实现方可以安全地在回调中读写非线程安全的实例状态。</p>
 */
public interface RemoteStreamListener {

    /**
     * 建立连接成功后的回调。
     */
    default void onOpen() {
    }

    /**
     * 收到一行上游响应后的回调。
     */
    void onLine(String line) throws Exception;

    /**
     * 流正常结束后的回调。
     */
    default void onClosed() {
    }

    /**
     * 网络失败、上游异常状态码或本地解析异常时的回调。
     */
    default void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
    }
}
