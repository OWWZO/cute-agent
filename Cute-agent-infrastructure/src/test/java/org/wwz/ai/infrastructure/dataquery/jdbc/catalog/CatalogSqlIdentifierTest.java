package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;

import org.junit.Test;
import org.wwz.ai.infrastructure.dataquery.jdbc.catalog.clickhouse.ClickhouseCatalog;
import org.wwz.ai.infrastructure.dataquery.jdbc.catalog.mysql.MySqlCatalog;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 目录 SQL 的 schema/table 来自调用方，不能把引号或注释符拼进 SQL。
 */
public class CatalogSqlIdentifierTest {

    @Test
    public void clickhouseRejectsInjectionSchemaBeforeExecutingSql() {
        AtomicReference<String> sql = new AtomicReference<>();
        Connection connection = fakeConnection(sql, false);
        try {
            new ClickhouseCatalog().listTables(connection, "x' OR 1=1 --");
            fail("ClickHouse 恶意 schema 必须被拒绝");
        } catch (CatalogException expected) {
            assertTrue(expected.getMessage().contains("非法schema标识符"));
        }
        assertFalse("恶意输入不应进入 executeQuery", sql.get() != null);
    }

    @Test
    public void clickhouseStillQueriesNormalSchema() {
        AtomicReference<String> sql = new AtomicReference<>();
        new ClickhouseCatalog().listTables(fakeConnection(sql, false), "sales");
        assertTrue(sql.get(), sql.get().contains("database = 'sales'"));
    }

    @Test
    public void mysqlRejectsInjectionSchemaAndTableBeforeExecutingSql() {
        for (String[] attack : new String[][]{
                {"x' OR 1=1 --", "orders"},
                {"sales", "orders' OR 1=1 --"}
        }) {
            AtomicReference<String> sql = new AtomicReference<>();
            Connection connection = fakeConnection(sql, true);
            try {
                new MySqlCatalog().getTableColumns(connection, attack[1], attack[0]);
                fail("MySQL 恶意 schema/table 必须被拒绝");
            } catch (CatalogException expected) {
                assertTrue(expected.getMessage().contains("非法"));
            }
            assertFalse("恶意输入不应进入 executeQuery", sql.get() != null);
        }
    }

    @Test
    public void mysqlStillQueriesNormalSchemaAndTable() {
        AtomicReference<String> sql = new AtomicReference<>();
        new MySqlCatalog().getTableColumns(fakeConnection(sql, true), "orders", "sales");
        assertTrue(sql.get(), sql.get().contains("TABLE_SCHEMA IN  ('sales')"));
        assertTrue(sql.get(), sql.get().contains("TABLE_NAME ='orders'"));
    }

    private static Connection fakeConnection(AtomicReference<String> capturedSql, boolean metadataResultSet) {
        ResultSet resultSet = fakeResultSet(metadataResultSet);
        Statement statement = (Statement) Proxy.newProxyInstance(
                CatalogSqlIdentifierTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        capturedSql.set((String) args[0]);
                        return resultSet;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(
                CatalogSqlIdentifierTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return statement;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet fakeResultSet(boolean metadataColumns) {
        return (ResultSet) Proxy.newProxyInstance(
                CatalogSqlIdentifierTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (metadataColumns && ("getString".equals(method.getName())
                            || "getBoolean".equals(method.getName()))) {
                        return "close".equals(method.getName()) ? Boolean.FALSE : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
