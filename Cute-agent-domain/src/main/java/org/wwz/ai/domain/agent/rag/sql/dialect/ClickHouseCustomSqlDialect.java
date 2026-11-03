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
        buf.append(val.replace(literalEndQuoteString, literalEscapedQuote));
        buf.append(literalEndQuoteString);
    }
}
