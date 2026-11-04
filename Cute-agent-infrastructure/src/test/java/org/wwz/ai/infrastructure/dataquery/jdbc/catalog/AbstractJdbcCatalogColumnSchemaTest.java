package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;

import org.junit.Test;
import org.wwz.ai.infrastructure.dataquery.jdbc.catalog.clickhouse.ClickhouseCatalog;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** 列元数据查询必须使用调用方传入的 schema，并正确处理 ClickHouse 的限定表名。 */
public class AbstractJdbcCatalogColumnSchemaTest {

    @Test
    public void defaultColumnLookupUsesCallerSchemaAsCatalog() {
        Object[] parameters = captureGetColumns(new AbstractJdbcCatalog() {
        }, "orders", "sales");

        assertEquals("sales", parameters[0]);
        assertNull(parameters[1]);
        assertEquals("orders", parameters[2]);
    }

    @Test
    public void defaultColumnLookupDoesNotFilterForNullOrBlankSchema() {
        for (String schema : new String[]{null, "   "}) {
            Object[] parameters = captureGetColumns(new AbstractJdbcCatalog() {
            }, "orders", schema);

            assertNull(parameters[0]);
        }
    }

    @Test
    public void clickhouseColumnLookupSplitsDatabaseQualifiedTable() {
        Object[] parameters = captureGetColumns(new ClickhouseCatalog(), "analytics.orders", "analytics");

        assertEquals("analytics", parameters[0]);
        assertEquals("orders", parameters[2]);
    }

    private static Object[] captureGetColumns(AbstractJdbcCatalog catalog, String tablePath, String schema) {
        AtomicReference<Object[]> captured = new AtomicReference<>();
        ResultSet resultSet = emptyResultSet();
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                AbstractJdbcCatalogColumnSchemaTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName())) {
                        captured.set(Arrays.copyOf(args, 4));
                        return resultSet;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                AbstractJdbcCatalogColumnSchemaTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        catalog.getTableColumns(connection, tablePath, schema);
        assertNotNull("DatabaseMetaData.getColumns 必须被调用", captured.get());
        return captured.get();
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                AbstractJdbcCatalogColumnSchemaTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
