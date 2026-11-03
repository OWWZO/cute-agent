package org.wwz.ai.domain.agent.runtime.llm;

import java.util.function.Consumer;

/**
 * LLM 出站能力端口。
 * <p>
 * Domain 只负责请求编排和流式语义；供应商 SDK、消息映射和网络实现由 Adapter 承担。
 */
public interface LlmCompletionPort {

    /**
     * 执行一次非流式 completion。
     * <p>{@code request} 必须非 null；失败以非受检异常抛出（供应商错误由适配器映射为运行时异常）；
     * 返回值可能为 {@code null}，调用方须按空补全处理（{@code LlmResilientCompletionPort} 会据此重试）。</p>
     */
    LlmResponse complete(LlmRequest request);

    /**
     * 打开一次惰性流式 completion。调用 subscribe 前不得连接供应商。
     */
    StreamCall stream(LlmRequest request);

    interface StreamCall {
        /**
         * 订阅本次流式调用。
         * <p>每次订阅最多收到一次终态回调（{@code onError} 与 {@code onComplete} 二选一）；终态回调之后
         * 以及 {@link #cancel()} 之后不得再投递 {@code onEvent}。回调线程由适配器决定，可能是供应商/IO
         * 线程；{@code onEvent} 不应长时间阻塞（不做背压保证）。同一个 {@code StreamCall} 只应 subscribe
         * 一次，调用方负责串行化 subscribe / cancel。</p>
         */
        void subscribe(Consumer<LlmStreamEvent> onEvent,
                       Consumer<Throwable> onError,
                       Runnable onComplete);

        /**
         * 取消本次流式调用。
         * <p>幂等且静默：不触发 {@code onError} / {@code onComplete}，取消后不得再投递事件。
         * 在 subscribe 之前调用会直接关闭本次调用，后续 subscribe 不再建立连接。实现应释放底层连接/订阅资源。</p>
         */
        void cancel();
    }
}
