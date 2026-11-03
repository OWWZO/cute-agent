package org.wwz.ai.domain.agent.adapter.port;

import java.io.IOException;

/**
 * 通用远端 HTTP 调用端口。
 * 用于把 domain 中的同步 HTTP 技术细节下沉到 infrastructure。
 */
public interface RemoteHttpPort {

    /**
     * 执行一次同步 HTTP 请求并返回文本响应（成功时；非 2xx 抛异常）。
     */
    String execute(RemoteHttpRequest request) throws IOException;

    /**
     * 执行一次同步 HTTP 请求，返回状态码/响应头/正文。
     * 不因 3xx 抛错，便于业务自行处理 redirect。
     * <p>必须返回真实响应：不支持详细响应的适配器保留本默认实现的快速失败，
     * 不得用 {@link #execute(RemoteHttpRequest)} 伪造 200 成功响应——
     * 那会让重定向/4xx/5xx 分支永远不可达，并把失败伪装成成功。</p>
     */
    default RemoteHttpResponse executeDetailed(RemoteHttpRequest request) throws IOException {
        throw new UnsupportedOperationException(
                getClass().getName() + " 未实现 executeDetailed，无法返回真实状态码/响应头");
    }
}
