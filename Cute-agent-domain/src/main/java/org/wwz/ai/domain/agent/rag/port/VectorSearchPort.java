package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchResult;

/**
 * 向量检索端口。
 * <p>存储端失败以 {@link VectorPortException}（unchecked）抛出，调用方可按需捕获做重试/降级。</p>
 */
public interface VectorSearchPort {

    VectorSearchResult search(VectorSearchRequest request);
}
