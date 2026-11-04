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

    private static JdbcDataMetaProvider providerWithColumns(int columnCount) {
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
                    case "executeQuery" -> resultSet;
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
        wrapper.setJdbcDialect(new H2Dialect());
        return new JdbcDataMetaProvider() {
            @Override
            protected ConnectionWrapper getConnection(JdbcConnectionConfig config) {
                return wrapper;
            }
        };
    }
}
