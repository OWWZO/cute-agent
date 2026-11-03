package org.wwz.ai.infrastructure.dataquery.provider.jdbc;

import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTable;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;

import java.sql.SQLException;
import java.util.List;

/**
 * JDBC 元数据读取契约。
 *
 * <p>方法刻意保留 {@link SQLException}，由上层适配器统一翻译为运行时异常。这里的
 * schema 参数并不完全同义：表列表使用 JDBC schema pattern，列查询使用数据库
 * schema 名称；具体方言目录可以进一步解释或忽略该参数。</p>
 */
public interface DataMetaProvider<T extends JdbcQueryRequest> {
    /** 按 JDBC schema pattern 查询表；null 的匹配范围由具体目录实现决定。 */
    List<DataQueryTable> queryTables(T request, String schemaPattern) throws SQLException;

    /** 按数据库 schema 查询列；方言目录不支持 schema 过滤时可以忽略该参数。 */
    List<DataQueryTableColumn> queryColumns(T request, String tableName, String schema) throws SQLException;

    /**
     * 执行只读查询并读取结果集列定义。
     *
     * <p>request.sql 必须非空且应当是只读、可执行的查询；实现只映射 ResultSetMetaData，
     * 但不会因此省掉数据库执行开销。</p>
     */
    List<DataQueryTableColumn> getTableColumnsOfSql(T request) throws SQLException;
}
