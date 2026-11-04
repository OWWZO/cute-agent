package org.wwz.ai.infrastructure.dataquery.jdbc.dialect;

import org.junit.Test;
import org.wwz.ai.infrastructure.dataquery.jdbc.dialect.mysql.MysqlDialect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** 分页片段必须与合法 SQL 拼接，并避免大页码下 int 乘法溢出。 */
public class JdbcDialectPagingSqlTest {

    private final JdbcDialect dialect = new MysqlDialect();

    @Test
    public void formatPagingSqlRemovesTrailingSemicolon() {
        assertEquals("SELECT * FROM t LIMIT 0,10",
                dialect.formatPagingSql(1, 10, "SELECT * FROM t;"));
    }

    @Test
    public void formatPagingSqlRemovesTrailingSemicolonAndWhitespace() {
        assertEquals("SELECT * FROM t LIMIT 0,10",
                dialect.formatPagingSql(1, 10, "SELECT * FROM t;  \n"));
    }

    @Test
    public void formatPagingSqlKeepsSqlWithoutSemicolonUnchanged() {
        assertEquals("SELECT * FROM t LIMIT 0,10",
                dialect.formatPagingSql(1, 10, "SELECT * FROM t"));
    }

    @Test
    public void formatPagingSqlUsesLongOffset() {
        String sql = dialect.formatPagingSql(100000, 100000, "SELECT 1");

        assertFalse(sql, sql.contains("LIMIT -"));
        assertEquals("SELECT 1 LIMIT 9999900000,100000", sql);
    }

    @Test
    public void stripTrailingSemicolonHandlesNull() {
        assertEquals("", JdbcDialect.stripTrailingSemicolon(null));
    }
}
