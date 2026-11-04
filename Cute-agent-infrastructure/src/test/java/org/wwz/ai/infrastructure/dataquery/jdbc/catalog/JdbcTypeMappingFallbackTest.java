package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;

import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.domain.agent.rag.model.schema.StandardColumnType;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.wwz.ai.infrastructure.dataquery.jdbc.catalog.AbstractJdbcCatalog.toJdbcType;

/** 驱动返回非标准 JDBC 类型码时必须降级，不能中断整列元数据查询。 */
public class JdbcTypeMappingFallbackTest {

    @Test
    public void toJdbcTypeKeepsStandardType() {
        assertEquals(JDBCType.VARCHAR, toJdbcType(Types.VARCHAR));
    }

    @Test
    public void toJdbcTypeDegradesPostgresUuid() {
        assertEquals(JDBCType.OTHER, toJdbcType(2950));
    }

    @Test
    public void toJdbcTypeDegradesUnknownTypeCode() {
        assertEquals(JDBCType.OTHER, toJdbcType(Integer.MAX_VALUE));
    }

    @Test
    public void unknownTypeConvertsToVarchar() {
        assertEquals(StandardColumnType.VARCHAR.name(),
                AbstractJdbcCatalog.typeConvert(toJdbcType(2950)));
    }

    @Test
    public void abstractCatalogKeepsQueryingAfterUnknownColumnType() {
        List<DataQueryTableColumn> columns = new AbstractJdbcCatalog() {
        }.getTableColumns(unknownTypeConnection(), "orders", "sales");

        assertEquals(1, columns.size());
        assertEquals(JDBCType.OTHER.name(), columns.get(0).getOriginDataType());
        assertEquals(StandardColumnType.VARCHAR.name(), columns.get(0).getDataType());
    }

    private static Connection unknownTypeConnection() {
        AtomicBoolean hasRow = new AtomicBoolean(true);
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                JdbcTypeMappingFallbackTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "next" -> hasRow.getAndSet(false);
                        case "getString" -> "COLUMN_NAME".equals(args[0]) ? "payload" : "json payload";
                        case "getInt" -> switch ((String) args[0]) {
                            case "DATA_TYPE" -> 2950;
                            case "NULLABLE" -> DatabaseMetaData.columnNullable;
                            case "ORDINAL_POSITION" -> 1;
                            default -> 0;
                        };
                        case "getObject" -> null;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                JdbcTypeMappingFallbackTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getColumns".equals(method.getName())) {
                        return resultSet;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(
                JdbcTypeMappingFallbackTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
