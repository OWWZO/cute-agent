package org.wwz.ai.test.domain.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.SqlReadOnlyGuard;

/**
 * testQuery 只读门禁回归：修复前调用方 SQL 原样进入 JDBC，可执行 DDL/DML 与多语句。
 */
public class SqlReadOnlyGuardTest {

    @Test
    public void acceptsSingleReadOnlyStatements() {
        Assert.assertEquals("SELECT 1", SqlReadOnlyGuard.requireReadOnly("SELECT 1"));
        Assert.assertEquals("select * from sales_data limit 10",
                SqlReadOnlyGuard.requireReadOnly("select * from sales_data limit 10;"));
        Assert.assertEquals("SELECT * FROM t",
                SqlReadOnlyGuard.requireReadOnly("-- comment\nSELECT * FROM t"));
        Assert.assertEquals("WITH x AS (SELECT 1) SELECT * FROM x",
                SqlReadOnlyGuard.requireReadOnly("/* hint */ WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    public void rejectsWritesAndDdl() {
        assertRejected("UPDATE sales_data SET amount = 0");
        assertRejected("DELETE FROM sales_data");
        assertRejected("INSERT INTO sales_data VALUES (1)");
        assertRejected("DROP TABLE sales_data");
        assertRejected("ALTER TABLE sales_data ADD COLUMN x INT");
        assertRejected("TRUNCATE TABLE sales_data");
        assertRejected("CREATE TABLE x (id INT)");
        assertRejected("/*x*/ UPDATE sales_data SET amount = 0");
        assertRejected("SET @x = 1");
    }

    @Test
    public void rejectsMultipleStatements() {
        assertRejected("SELECT 1; DROP TABLE sales_data");
        assertRejected("SELECT 1; SELECT 2");
    }

    @Test
    public void rejectsEmptyStatement() {
        assertRejected(null);
        assertRejected("   ");
        assertRejected("-- only comment");
    }

    private void assertRejected(String sql) {
        try {
            SqlReadOnlyGuard.requireReadOnly(sql);
            Assert.fail("应拒绝 SQL: " + sql);
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
