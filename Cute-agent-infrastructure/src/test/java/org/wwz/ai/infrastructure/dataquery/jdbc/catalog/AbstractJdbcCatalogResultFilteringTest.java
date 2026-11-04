package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;

import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** JDBC 列元数据的 LIKE 查询结果必须再按真实表名精确收敛。 */
public class AbstractJdbcCatalogResultFilteringTest {

    @Test
    public void columnLookupExcludesOtherTablesMatchedByUnderscorePattern() {
        String[][] rows = {
                {"order_item", "id"},
                {"orderXitem", "unrelated_id"},
                {"ORDER_ITEM", "total"}
        };

        List<DataQueryTableColumn> columns = new AbstractJdbcCatalog() {
        }.getTableColumns(connectionWithColumns(rows), "order_item", "sales");

        assertEquals(Arrays.asList("id", "total"),
                columns.stream().map(DataQueryTableColumn::getName).toList());
    }

    private static Connection connectionWithColumns(String[][] rows) {
        AtomicInteger cursor = new AtomicInteger(-1);
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                AbstractJdbcCatalogResultFilteringTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> cursor.incrementAndGet() < rows.length;
                    case "getString" -> switch ((String) args[0]) {
                        case "TABLE_NAME" -> rows[cursor.get()][0];
                        case "COLUMN_NAME" -> rows[cursor.get()][1];
                        case "REMARKS" -> "";
                        default -> throw new UnsupportedOperationException((String) args[0]);
                    };
                    case "getInt" -> switch ((String) args[0]) {
                        case "DATA_TYPE" -> Types.VARCHAR;
                        case "NULLABLE" -> DatabaseMetaData.columnNullable;
                        case "ORDINAL_POSITION" -> cursor.get() + 1;
                        default -> 0;
                    };
                    case "getObject" -> null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                AbstractJdbcCatalogResultFilteringTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName())) {
                        return resultSet;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(
                AbstractJdbcCatalogResultFilteringTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
