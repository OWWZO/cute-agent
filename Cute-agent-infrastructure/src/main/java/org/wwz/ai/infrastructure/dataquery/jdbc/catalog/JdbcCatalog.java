
package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;




import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTable;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;

import java.sql.Connection;
import java.util.List;

/**
 * JDBC 元数据目录抽象。
 *
 * <p>目录只负责从已建立的连接读取表和列的结构信息，并转换为数据问数领域模型；
 * 不负责连接创建、连接池管理或 SQL 查询执行。不同数据库的元数据差异由具体目录
 * 实现隔离。连接生命周期归调用方所有，实现不得关闭、提交、回滚连接，也不得修改
 * auto-commit、read-only 或事务隔离级别。</p>
 */
public interface JdbcCatalog {

    /**
     * 查询指定 schema 下可供问数使用的表。
     *
     * @param schema 方言定义的 schema 过滤值；null/空白的解释由具体目录实现决定
     */
    List<DataQueryTable> listTables(Connection connection, String schema) throws CatalogException;

    /**
     * 查询指定表的列信息，并保留原始类型与统一类型。
     *
     * @param tablePath 表名或方言定义的限定路径；默认 JDBC 实现按 tableNamePattern 使用
     * @param schema    方言定义的 schema 过滤值；null/空白的解释由具体目录实现决定
     */
    List<DataQueryTableColumn> getTableColumns(Connection connection, String tablePath, String schema) throws CatalogException;
}
