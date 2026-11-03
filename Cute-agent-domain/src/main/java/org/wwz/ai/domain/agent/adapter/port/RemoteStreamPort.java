package org.wwz.ai.domain.agent.adapter.port;

import java.io.IOException;

/**
 * 通用远端流式调用端口。
 * 用于把 domain 中依赖 OkHttp SSE / Callback 的实现统一下沉到 infrastructure。
 */
public interface RemoteStreamPort {

    /**
     * 发起一次流式 HTTP 请求，并返回可取消的会话句柄。
     *
     * <p>{@code request} / {@code listener} 必须非 null：当前适配器用
     * {@link java.util.Objects#requireNonNull} 快速失败。</p>
     *
     * <p>建连与读流阶段的失败不通过本方法抛出，而是异步回调
     * {@link RemoteStreamListener#onFailure}；这种情况下不会返回 session。
     * 若实现选择同步抛出 {@link IOException}，必须在此之前释放全部已分配资源，
     * 且不得再回调 listener。</p>
     */
    RemoteStreamSession openStream(RemoteStreamRequest request, RemoteStreamListener listener) throws IOException;
}
