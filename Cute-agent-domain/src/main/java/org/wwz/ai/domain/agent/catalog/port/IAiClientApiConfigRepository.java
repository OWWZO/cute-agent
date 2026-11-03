package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.AiClientApiConfig;

import java.util.List;
import java.util.Optional;

/**
 * API 配置持久化端口。
 * <p>写方法返回 {@code false} 表示未影响任何行（目标行不存在、已删除，或提交值与库内一致）；
 * 持久化/基础设施错误必须抛出，不得吞掉后返回 {@code false}。{@code apiId} 是唯一业务键（DB 唯一索引），
 * 调用方须传入非空非空白值。</p>
 */
public interface IAiClientApiConfigRepository {

    /** 默认分页安全上限：旧的无参查询最多读取这么多行。 */
    int DEFAULT_LIST_LIMIT = 500;

    boolean insert(AiClientApiConfig config);

    boolean updateById(AiClientApiConfig config);

    boolean updateByApiId(AiClientApiConfig config);

    boolean deleteById(Long id);

    boolean deleteByApiId(String apiId);

    Optional<AiClientApiConfig> findById(Long id);

    Optional<AiClientApiConfig> findByApiId(String apiId);

    /** 启用配置；默认最多 {@link #DEFAULT_LIST_LIMIT} 行，持久化层负责 LIMIT。 */
    default List<AiClientApiConfig> findEnabled() {
        return findEnabled(DEFAULT_LIST_LIMIT);
    }

    /**
     * 启用配置（最多 {@code limit} 行）。
     * <p>用于运行时加载的小集合；{@code limit <= 0} 时取默认上限。</p>
     */
    List<AiClientApiConfig> findEnabled(int limit);

    /** 全量配置的默认分页入口；等价于 {@code findAll(0, DEFAULT_LIST_LIMIT)}。 */
    default List<AiClientApiConfig> findAll() {
        return findAll(0, DEFAULT_LIST_LIMIT);
    }

    /** 按 {@code create_time DESC} 分页读取全部配置。实现必须把 LIMIT 下推到 SQL。 */
    List<AiClientApiConfig> findAll(int offset, int limit);
}
