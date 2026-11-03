package org.wwz.ai.domain.agent.rag;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 数据查询工作台「执行 SQL」入口的只读门禁。
 * <p>请求体里的 SQL 完全由调用方提供，必须在进入 JDBC 之前拒绝写操作与多语句：
 * 只允许单条 {@code SELECT} / {@code WITH} 查询，DDL/DML、会话控制语句与语句拼接一律拒绝。</p>
 * <p>这是纵深防御的一层，不替代端点鉴权与数据库只读账号；三者的任意一层缺失都可能放大风险。</p>
 */
public final class SqlReadOnlyGuard {

    /** 出现在任意位置都视为写操作/会话控制的关键字（按整词匹配，避免误伤 update_time 这类列名）。 */
    private static final Pattern FORBIDDEN_KEYWORD = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|MERGE|CALL|EXEC|EXECUTE"
                    + "|REPLACE|LOAD|ATTACH|DETACH|PRAGMA|VACUUM|BEGIN|COMMIT|ROLLBACK|SET|USE|INTO"
                    + "|SHUTDOWN|KILL)\\b");

    private SqlReadOnlyGuard() {
    }

    /**
     * 校验通过则返回归一化后的语句；不通过抛 {@link IllegalArgumentException}。
     */
    public static String requireReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalized = stripLeadingComments(sql).trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        // 多语句：去掉末尾分号后仍有分号，说明拼接了第二条语句。
        if (normalized.indexOf(';') >= 0) {
            throw new IllegalArgumentException("只允许执行单条只读查询");
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException("只允许执行 SELECT 查询");
        }
        if (FORBIDDEN_KEYWORD.matcher(normalized).find()) {
            throw new IllegalArgumentException("只允许执行只读查询，禁止 DDL/DML 与会话控制语句");
        }
        return normalized;
    }

    /** 跳过语句开头的行注释与块注释，避免用注释绕过首关键字判断。 */
    private static String stripLeadingComments(String sql) {
        String text = sql;
        boolean changed = true;
        while (changed) {
            changed = false;
            String trimmed = text.stripLeading();
            if (trimmed.startsWith("--")) {
                int lineEnd = trimmed.indexOf('\n');
                text = lineEnd < 0 ? "" : trimmed.substring(lineEnd + 1);
                changed = true;
            } else if (trimmed.startsWith("/*")) {
                int commentEnd = trimmed.indexOf("*/");
                text = commentEnd < 0 ? "" : trimmed.substring(commentEnd + 2);
                changed = true;
            } else {
                text = trimmed;
            }
        }
        return text;
    }
}
