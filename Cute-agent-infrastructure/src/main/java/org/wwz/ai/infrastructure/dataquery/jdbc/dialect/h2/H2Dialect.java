package org.wwz.ai.infrastructure.dataquery.jdbc.dialect.h2;





import org.wwz.ai.infrastructure.dataquery.jdbc.dialect.DialectEnum;
import org.wwz.ai.infrastructure.dataquery.jdbc.dialect.JdbcDialect;

import java.util.Properties;

/** H2 方言，主要用于本地/测试环境并兼容 MySQL 模式的元数据属性。 */
public class H2Dialect implements JdbcDialect {
    @Override
    public DialectEnum dialectName() {
        return DialectEnum.H2;
    }

    @Override
    public String driverName() {
        return "org.h2.Driver";
    }

    @Override
    public Properties defaultProperties() {
        // H2 的 MySQL 兼容语法由 URL 的 MODE=MySQL 控制；这里的属性沿用连接池元数据配置。
        Properties properties = new Properties();
        properties.setProperty("remarks", "true");
        properties.setProperty("useInformationSchema", "true");
        return properties;
    }
}
