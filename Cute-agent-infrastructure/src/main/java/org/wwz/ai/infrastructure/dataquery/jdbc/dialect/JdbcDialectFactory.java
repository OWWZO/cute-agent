package org.wwz.ai.infrastructure.dataquery.jdbc.dialect;

/**
 * 根据 JDBC URL 判断并创建数据库方言的 SPI 工厂。
 *
 * <p>实现必须无状态且线程安全。加载器按 ServiceLoader 返回顺序取第一个匹配工厂，
 * 因此多个工厂的 URL 前缀不应重叠，除非明确接受该顺序。</p>
 */
public interface JdbcDialectFactory {

    /**
     * 判断该工厂是否声明支持给定 JDBC URL。
     *
     * @param url 非空 JDBC URL；实现不得依赖此前调用留下的状态
     */
    boolean acceptsURL(String url);

    /**
     * 创建与最近一次匹配 URL 对应的方言实现。
     *
     * <p>返回实例会被并发复用，必须无状态且线程安全；创建失败时可抛出未检查异常。</p>
     */
    JdbcDialect create();
}
