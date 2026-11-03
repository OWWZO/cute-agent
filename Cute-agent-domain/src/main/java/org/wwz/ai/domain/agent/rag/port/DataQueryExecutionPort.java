package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;

/**
 * 数据查询执行端口。
 * domain 只描述“基于问数数据源执行 SQL”这件事，JDBC 连接与方言细节由 infrastructure 承接。
 * <p>执行失败以 {@link IllegalStateException} 抛出，不通过返回值的 success 标记表达。</p>
 */
public interface DataQueryExecutionPort {

    /**
     * 执行 SQL 查询并返回结构化结果。
     */
    SqlExecutionResult query(String sql);

    /**
     * 执行带 limit 的 SQL 查询。
     */
    SqlExecutionResult query(String sql, int limit);
}
