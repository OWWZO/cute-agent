package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.column.ColumnValueDocument;

import java.util.List;

/**
 * 列值索引管理端口。
 */
public interface ColumnValueIndexAdminPort {

    /**
     * 底层检索能力是否可用（客户端已配置且可连接），不代表索引已经存在。
     * <p>调用方（如 {@code ColumnValueSyncService.initColumnValueIndex}）把它当作初始化前置条件，
     * 因此实现不能因为索引缺失而返回 false；false 只表示能力整体不可用。</p>
     */
    boolean isAvailable();

    /**
     * 幂等初始化：索引已存在时复用（可补齐必要配置），不存在时创建。
     * <p>能力不可用或创建失败时以非受检异常表达（当前实现抛 {@link IllegalStateException}），
     * 不会静默 no-op。</p>
     */
    void initializeIndex();

    /**
     * 重建索引：删除既有索引后重新创建，会丢弃全部既有列值数据。
     * <p>索引不存在时直接创建；能力不可用、删除或创建失败时抛 {@link IllegalStateException}。
     * 删除与创建之间索引短暂缺失，不是原子操作。</p>
     */
    void recreateIndex();

    /**
     * @param indexName 索引名，调用方必须传非空值
     * @return true 表示索引存在；false 表示不存在、客户端不可用或检查失败（失败会被记录日志）
     */
    boolean indexExists(String indexName);

    /**
     * 创建索引。
     *
     * @param indexName  索引名，调用方必须传非空值；端口不额外校验，空值会透传给存储端
     * @param definition 索引定义（JSON 字符串）；空值表示使用存储端默认配置
     * @return true 表示创建成功；false 表示客户端不可用或创建失败（失败会被记录日志，不抛异常）
     */
    boolean createIndex(String indexName, String definition);

    /**
     * 删除索引。
     *
     * @param indexName 索引名，调用方必须传非空值；端口不额外校验，空值会透传给存储端
     * @return true 表示删除成功；false 表示索引不存在、客户端不可用或删除失败
     */
    boolean deleteIndex(String indexName);

    /**
     * 批量写入列值文档。
     * <p>不是全有或全无：批量请求中部分文档失败时整体返回 false，但已写入的文档不会回滚；
     * 文档以 {@code valueId} 为 id，重试会覆盖同一批文档。</p>
     *
     * @param indexName 索引名，调用方必须传非空值；端口不额外校验，空值会透传给存储端
     * @param documents 待写入文档；null 或空列表时返回 false（no-op）
     * @return true 表示批量请求无错误；false 表示未执行或存在写入错误
     */
    boolean bulkInsert(String indexName, List<ColumnValueDocument> documents);
}
