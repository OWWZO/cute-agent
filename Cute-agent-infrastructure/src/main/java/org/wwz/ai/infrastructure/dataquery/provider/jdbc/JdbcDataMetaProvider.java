package org.wwz.ai.infrastructure.dataquery.provider.jdbc;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTable;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcConnectionConfig;
import org.wwz.ai.infrastructure.dataquery.jdbc.connection.ConnectionWrapper;
import org.wwz.ai.infrastructure.dataquery.jdbc.connection.JdbcConnectionFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.wwz.ai.infrastructure.dataquery.jdbc.catalog.AbstractJdbcCatalog.typeConvert;
import static org.wwz.ai.infrastructure.dataquery.jdbc.catalog.AbstractJdbcCatalog.toJdbcType;


/**
 * JDBC 元数据查询适配器。
 *
 * <p>表/列目录查询委托给与数据源匹配的 {@code JdbcCatalog}；SQL 字段探测会执行
 * 查询并读取 ResultSetMetaData，但不把结果行物化到领域查询结果。驱动仍可能按照
 * limit 预取部分行，调用方不能把该路径视为零执行开销。</p>
 */
@Slf4j
@Service
public class JdbcDataMetaProvider implements DataMetaProvider<JdbcQueryRequest> {

    @Override
    public List<DataQueryTable> queryTables(JdbcQueryRequest request, String schemaPattern) throws SQLException {
        try (ConnectionWrapper wrapper = JdbcConnectionFactory.getConnection(request.getJdbcConnectionConfig());
             Connection connection = wrapper.getConnection()) {
            return wrapper.getCatalog().listTables(connection, schemaPattern);
        }
    }

    @Override
    public List<DataQueryTableColumn> queryColumns(JdbcQueryRequest request, String tableName, String schema) throws SQLException {
        try (ConnectionWrapper wrapper = JdbcConnectionFactory.getConnection(request.getJdbcConnectionConfig());
             Connection connection = wrapper.getConnection()) {
            return wrapper.getCatalog().getTableColumns(connection, tableName, schema);
        }
    }

    @Override
    public List<DataQueryTableColumn> getTableColumnsOfSql(JdbcQueryRequest request) throws SQLException {
        try (ConnectionWrapper wrapper = getConnection(request.getJdbcConnectionConfig())) {
            // 元数据查询同样使用方言格式化和 limit，保证字段探测与真正执行 SQL 的约束一致。
            String formattedSql = wrapper.getJdbcDialect().formatSql(request.getSql());
            log.info("jdbc meta 执行sql:{}", formattedSql);
            List<DataQueryTableColumn> columnList = new ArrayList<>();
            try (Connection connection = wrapper.getConnection()) {
            try (
                    Statement ps = wrapper.createStatement(connection, request.getLimit());
                    ResultSet rs = ps.executeQuery(formattedSql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                // 只把 ResultSetMetaData 映射为列定义，不遍历结果行；SQL 本身仍由 executeQuery 执行。
                for (int i = 1; i <= columnCount; i++) {
                    String name = metaData.getColumnLabel(i);
                    int intDataType = metaData.getColumnType(i);
                    JDBCType jdbcType = toJdbcType(intDataType);
                    String dataType = typeConvert(jdbcType);
                    DataQueryTableColumn column = DataQueryTableColumn.builder().name(name)
                            .comment(name)
                            .dataType(dataType)
                            .originDataType(jdbcType.name())
                            .position(i)
                            .build();
                    columnList.add(column);
                }
            }

            }
            return columnList;
        }
    }

    protected ConnectionWrapper getConnection(JdbcConnectionConfig config)
            throws SQLException {
        return JdbcConnectionFactory.getConnection(config);
    }

}
