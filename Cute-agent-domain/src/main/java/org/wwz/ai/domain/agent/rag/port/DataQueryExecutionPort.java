package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;

/**
 * 数据查询执行端口。
 * domain 只描述“基于问数数据源执行 SQL”这件事，JDBC 连接与方言细节由 infrastructure 承接。
 * <p>执行失败以 {@link IllegalStateException} 抛出，不通过返回值的 success 标记表达。</p>
 *
 * <h2>安全契约</h2>
 * <ul>
 *   <li><b>只读：</b>本端口只接受单条只读 {@code SELECT}（含 {@code WITH}/{@code UNION}）。
 *       调用方必须先完成校验；实现方不得执行 DML/DDL 或多语句 SQL，
 *       并应使用只读连接/只读 Statement 执行。</li>
 *   <li><b>行数上限：</b>实现方必须把返回行数限制在调用方给定的 {@code limit} 内
 *       （{@code limit > 0} 时）；{@code limit <= 0} 时沿用实现自身的默认上限，
 *       绝不能无界读取。返回值中的 {@code dataList} 必须已按该上限截断。</li>
 *   <li>超过上限时直接截断返回，不抛异常；调用方负责决定是否向用户提示。</li>
 * </ul>
 */
public interface DataQueryExecutionPort {

    /**
     * 执行只读查询并返回结构化结果。
     * <p>等价于 {@code query(sql, 0)}：使用实现默认行数上限。</p>
     */
    SqlExecutionResult query(String sql);

    /**
     * 执行只读查询，并把返回行数限制在 {@code limit} 以内。
     *
     * @param limit 最大返回行数；{@code <= 0} 表示使用实现默认上限
     */
    SqlExecutionResult query(String sql, int limit);
}
