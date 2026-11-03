package org.wwz.ai.infrastructure.dataquery.jdbc.catalog;


import org.wwz.ai.infrastructure.dataquery.jdbc.dialect.DialectEnum;

/**
 * 为一种数据库方言创建对应元数据目录的 SPI 工厂。
 *
 * <p>工厂本身必须无状态且线程安全。目录实例会被连接池缓存并跨请求复用，因此也不得
 * 持有某个调用方传入的 Connection 或其他请求级状态。</p>
 */
public interface JdbcCatalogFactory {

    /** 返回该工厂支持的方言。 */
    DialectEnum jdbcDialect();

    /**
     * 创建无状态、线程安全的元数据目录实例；连接由调用方在每次调用时提供。
     *
     * @throws RuntimeException 初始化失败时由实现抛出的未检查异常会原样交给加载器
     */
    JdbcCatalog createCatalog();
}
