package org.wwz.ai.infrastructure.dataquery.provider.jdbc;

import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcConnectionConfig;
import org.wwz.ai.infrastructure.dataquery.jdbc.connection.ConnectionWrapper;
import org.wwz.ai.infrastructure.dataquery.jdbc.dialect.h2.H2Dialect;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** SQL 结果元数据的列序号必须与 JDBC 的 1 基序号一致。 */
public class JdbcDataMetaProviderTest {

    @Test
    public void sqlColumnPositionsStartAtOne() throws Exception {
        JdbcDataMetaProvider provider = providerWithColumns(2);

        List<DataQueryTableColumn> columns = provider.getTableColumnsOfSql(new JdbcQueryRequest());

        assertEquals(Integer.valueOf(1), columns.get(0).getPosition());
        assertEquals(Integer.valueOf(2), columns.get(1).getPosition());
    }

    @Test
    public void sqlMetadataLookupDoesNotRewriteCallerRequest() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        JdbcDataMetaProvider provider = providerWithColumns(1, new H2Dialect() {
            @Override
            public String formatSql(String sql) {
                return sql + " FORMATTED";
            }
        }, executedSql);
        JdbcQueryRequest request = new JdbcQueryRequest();
        request.setSql("SELECT 1");
        request.setLimit(1);

        provider.getTableColumnsOfSql(request);

        assertEquals("SELECT 1 FORMATTED", executedSql.get());
        assertEquals("SELECT 1", request.getSql());
    }

    private static JdbcDataMetaProvider providerWithColumns(int columnCount) {
        return providerWithColumns(columnCount, new H2Dialect(), new AtomicReference<>());
    }

    private static JdbcDataMetaProvider providerWithColumns(
            int columnCount, H2Dialect dialect, AtomicReference<String> executedSql) {
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                JdbcDataMetaProviderTest.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> columnCount;
                    case "getColumnLabel" -> "column" + args[0];
                    case "getColumnType" -> Types.VARCHAR;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                JdbcDataMetaProviderTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Statement statement = (Statement) Proxy.newProxyInstance(
                JdbcDataMetaProviderTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        executedSql.set((String) args[0]);
                        yield resultSet;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                JdbcDataMetaProviderTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ConnectionWrapper wrapper = new ConnectionWrapper() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Statement createStatement(Connection ignored, Integer fetchSize) {
                return statement;
            }

            @Override
            public void close() {
            }
        };
        wrapper.setJdbcDialect(dialect);
        return new JdbcDataMetaProvider() {
            @Override
            protected ConnectionWrapper getConnection(JdbcConnectionConfig config) {
                return wrapper;
            }
        };
    }
}
