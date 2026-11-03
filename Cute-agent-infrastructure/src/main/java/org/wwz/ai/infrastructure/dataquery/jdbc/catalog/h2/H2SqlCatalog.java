
package org.wwz.ai.infrastructure.dataquery.jdbc.catalog.h2;


import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTable;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.domain.agent.rag.model.schema.StandardColumnType;
import org.wwz.ai.infrastructure.dataquery.jdbc.catalog.AbstractJdbcCatalog;
import org.wwz.ai.infrastructure.dataquery.jdbc.catalog.CatalogException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** H2 测试/本地数据源的元数据目录，按 MySQL 模式读取 INFORMATION_SCHEMA。 */
@Slf4j
public class H2SqlCatalog extends AbstractJdbcCatalog {

    private static final String SELECT_COLUMNS_SQL_TEMPLATE =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA IN  ('%s') AND TABLE_NAME ='%s' ORDER BY ORDINAL_POSITION ASC";

    @Override
    public List<DataQueryTable> listTables(Connection connection, String schema) throws CatalogException {
        // H2 的 show tables 返回表名列表，列序号由结果集第一列定义。
        String sql = "show tables ";
        try (PreparedStatement prepared = connection.prepareStatement(sql);
             ResultSet rs = prepared.executeQuery()) {
            List<DataQueryTable> tables = new ArrayList<>();
            while (rs.next()) {
                DataQueryTable st = new DataQueryTable();
                st.setTableName(rs.getString(1));
                tables.add(st);
            }
            return tables;
        } catch (SQLException e) {
            throw new CatalogException("获取数据库表失败", e);
        }
    }

    public String typeConvertMysql(String type) {
        // H2 在 MySQL 模式下沿用常见类型名，因此复用同一套领域类型归一规则。
        return switch (type) {
            case "DATE", "TIME", "TIMESTAMP" -> StandardColumnType.DATE.name();
            case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL" ->
                    StandardColumnType.DECIMAL.name();
            default -> StandardColumnType.VARCHAR.name();
        };
    }

    @Override
    public List<DataQueryTableColumn> getTableColumns(Connection connection, String tablePath, String schema) throws CatalogException {
        // H2 约定使用 PUBLIC schema，并将表名转大写后查询系统目录。
        String sql = String.format(
                SELECT_COLUMNS_SQL_TEMPLATE, "PUBLIC", tablePath.toUpperCase());

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int i = 1;
            List<DataQueryTableColumn> columnList = new ArrayList<>();
            while (rs.next()) {
                String columnName = rs.getString("column_name");
                String jdbcDataType = rs.getString("data_type").toUpperCase();
                String dataType = typeConvertMysql(jdbcDataType);
                String comment = rs.getString("remarks");
                DataQueryTableColumn column = DataQueryTableColumn.builder().name(columnName)
                        .comment(comment)
                        .dataType(dataType)
                        .originDataType(jdbcDataType)
                        .nullable(rs.getBoolean("is_nullable"))
                        .position(i++)
                        .build();
                columnList.add(column);
            }
            return columnList;

        } catch (Exception e) {
            throw new CatalogException(
                    String.format("获取表字段信息失败 %s", tablePath), e);
        }
    }
}
