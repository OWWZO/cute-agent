package org.wwz.ai.domain.agent.rag.sql.dialect;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * MySQL SQL 方言适配器，统一字符串字面量转义行为。
 */
public class MysqlCustomSqlDialect extends MysqlSqlDialect {

    public static final SqlDialect DEFAULT = new MysqlCustomSqlDialect(DEFAULT_CONTEXT);

    public MysqlCustomSqlDialect(Context context) {
        super(context);
    }

    @Override
    public void quoteStringLiteral(StringBuilder buf, @Nullable String charsetName, String val) {
        // MySQL 默认把反斜杠当转义符（未开启 NO_BACKSLASH_ESCAPES）：必须先加倍反斜杠，
        // 再转义单引号。否则以 \ 结尾的值会渲染成 'abc\'，收尾引号被反斜杠吃掉，
        // 后面的 SQL 被并进字符串字面量——轻则语法错误，重则字面量注入。
        buf.append(literalQuoteString);
        buf.append(val.replace("\\", "\\\\").replace(literalEndQuoteString, literalEscapedQuote));
        buf.append(literalEndQuoteString);
    }
}
