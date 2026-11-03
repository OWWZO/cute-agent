package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchResult;

/**
 * 向量检索端口。
 * <p>存储端失败以 {@link VectorPortException}（unchecked）抛出，调用方可按需捕获做重试/降级。</p>
 * <p><b>输入/输出契约：</b>{@code request} 为 {@code null} 时抛 {@link IllegalArgumentException}；
 * 返回结果永不为 {@code null}，无匹配返回 {@link VectorSearchResult#empty()}。
 * 实现必须遵守 {@link VectorSearchRequest#getLimit()}（可再施加更小的实现上限，调用方应传正数），
 * 并尽量按 {@link VectorSearchRequest#getTimeoutMillis()} 限制耗时；超时以 {@link VectorPortException}
 * 抛出。</p>
 */
public interface VectorSearchPort {

    /**
     * @param request 检索请求；不可为 {@code null}
     * @return 非 {@code null} 的检索结果；无匹配时为空结果
     */
    VectorSearchResult search(VectorSearchRequest request);
}
