package org.wwz.ai.domain.agent.rag.sql.dialect;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlDialect;
import org.apache.commons.lang3.StringUtils;

/**
 * SQL 方言选择工具，按配置字符串返回 MySQL 或 ClickHouse Calcite 方言。
 */
@Slf4j
public class SqlDialectUtil {

    private SqlDialectUtil() {
    }

    public static SqlDialect fromDialectString(String dialectString) {
        String normalized = StringUtils.trimToEmpty(dialectString);
        if (StringUtils.equalsIgnoreCase("clickhouse", normalized)) {
            return ClickHouseCustomSqlDialect.DEFAULT;
        }
        if (StringUtils.isNotBlank(normalized) && !StringUtils.equalsIgnoreCase("mysql", normalized)) {
            // 配置写错时不再静默按 MySQL 生成 SQL，留一条可检索的告警。
            log.warn("不支持的 SQL 方言 '{}'，已回退 MySQL", dialectString);
        }
        return MysqlCustomSqlDialect.DEFAULT;
    }
}
