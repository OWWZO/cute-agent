package org.wwz.ai.test.domain.rag;

import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.sql.SqlLiteralUtil;

/**
 * 数值类型判定必须覆盖 Calcite 数值族里的 REAL/DOUBLE。
 */
public class SqlLiteralUtilTest {

    @Test
    public void realAndDoubleAreNumeric() {
        Assert.assertTrue(SqlLiteralUtil.isNumericValue(SqlTypeName.DOUBLE));
        Assert.assertTrue(SqlLiteralUtil.isNumericValue(SqlTypeName.REAL));
        Assert.assertTrue(SqlLiteralUtil.isNumericValue(SqlTypeName.DECIMAL));
        Assert.assertTrue(SqlLiteralUtil.isNumericValue(SqlTypeName.BIGINT));
    }

    @Test
    public void textTypesAreNotNumeric() {
        Assert.assertFalse(SqlLiteralUtil.isNumericValue(SqlTypeName.VARCHAR));
        Assert.assertFalse(SqlLiteralUtil.isNumericValue(SqlTypeName.BOOLEAN));
        Assert.assertFalse(SqlLiteralUtil.isNumericValue(null));
    }
}
