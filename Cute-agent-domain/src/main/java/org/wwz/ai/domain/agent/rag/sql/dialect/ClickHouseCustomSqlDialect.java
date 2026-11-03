package org.wwz.ai.domain.agent.rag.sql.dialect;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.ClickHouseSqlDialect;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * ClickHouse SQL 方言适配器，补充项目问数链路需要的字面量和标识符规则。
 */
public class ClickHouseCustomSqlDialect extends ClickHouseSqlDialect {

    public static final SqlDialect DEFAULT = new ClickHouseCustomSqlDialect(DEFAULT_CONTEXT);

    public ClickHouseCustomSqlDialect(Context context) {
        super(context);
    }

    @Override
    public void quoteStringLiteral(StringBuilder buf, @Nullable String charsetName, String val) {
        buf.append(literalQuoteString);
        // ClickHouse 反斜杠是转义符：先把它自身转义，再处理引号；
        // 否则 "C:\temp" 会被解析成制表符，尾部反斜杠还会吃掉收尾引号。
        buf.append(val.replace("\\", "\\\\").replace(literalEndQuoteString, literalEscapedQuote));
        buf.append(literalEndQuoteString);
    }
}
