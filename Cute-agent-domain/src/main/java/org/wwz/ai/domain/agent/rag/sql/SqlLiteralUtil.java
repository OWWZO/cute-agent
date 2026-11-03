package org.wwz.ai.domain.agent.rag.sql;


import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * SQL 字面量类型判断工具。
 */
public final class SqlLiteralUtil {

    private SqlLiteralUtil() {
    }

    /**
     * 是否数值类型。
     * <p>委托 Calcite 的类型族，避免手工维护类型集合时漏掉 REAL/DOUBLE 等成员。</p>
     */
    public static boolean isNumericValue(SqlTypeName typeName) {
        return typeName != null && typeName.getFamily() == SqlTypeFamily.NUMERIC;
    }
}
