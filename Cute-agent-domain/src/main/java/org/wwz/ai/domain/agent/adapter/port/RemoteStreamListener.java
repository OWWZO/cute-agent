package org.wwz.ai.domain.agent.adapter.port;

/**
 * 协议无关的远端流式监听器。
 * infrastructure 负责把底层 SSE/HTTP 分帧结果转成按行事件回调给 domain。
 */
public interface RemoteStreamListener {

    /**
     * 建立连接成功后的回调。
     */
    default void onOpen() {
    }

    /**
     * 收到一行上游响应后的回调。
     * <p>实现抛出异常表示中止本次流：适配器随后触发
     * {@link #onFailure(Throwable, Integer, String)}（携带当前响应码、空响应体）并取消底层调用，
     * 不再触发 {@link #onClosed()}。{@code line} 不会为 {@code null}。</p>
     */
    void onLine(String line) throws Exception;

    /**
     * 流正常结束后的回调。
     */
    default void onClosed() {
    }

    /**
     * 网络失败、上游异常状态码或本地解析异常时的回调。
     * <p>终态回调：触发后不会再收到 {@link #onLine} 或 {@link #onClosed}。
     * {@code statusCode}/{@code responseBody} 在纯网络失败或本地解析异常时为 {@code null}，
     * 实现不得假定其非空。默认实现为空操作，需要感知失败的实现必须覆盖并完成挂起的结果。</p>
     */
    default void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
    }
}
