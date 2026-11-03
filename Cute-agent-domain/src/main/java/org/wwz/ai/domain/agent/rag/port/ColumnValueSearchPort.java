package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchRequest;
import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchResult;

import java.io.IOException;

/**
 * 列值文本检索端口。
 */
public interface ColumnValueSearchPort {

    /**
     * 检索与查询文本匹配的列值。
     * <p>{@code request} 为 null 或底层客户端不可用时返回 {@link ColumnValueSearchResult#empty()}；
     * 无匹配同样返回空结果，不返回 null。存储端 IO 失败以 {@link IOException} 抛出。</p>
     *
     * @param request 检索请求；可为 null
     * @return 非 null 的检索结果；无匹配时为空结果
     */
    ColumnValueSearchResult search(ColumnValueSearchRequest request) throws IOException;
}
