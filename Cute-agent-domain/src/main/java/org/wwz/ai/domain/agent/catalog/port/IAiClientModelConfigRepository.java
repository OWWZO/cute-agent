package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.AiClientModelConfig;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置持久化端口。
 * <p>写方法返回 {@code false} 表示未影响任何行（目标行不存在或已删除）；持久化/基础设施错误必须抛出，
 * 不得吞掉后返回 {@code false}。{@code modelId} 不是唯一键，{@code updateByModelId}/{@code deleteByModelId}
 * 可能影响多行，调用方需自行确认影响范围。</p>
 */
public interface IAiClientModelConfigRepository {

    /** 默认分页安全上限：旧的无参查询最多读取这么多行。 */
    int DEFAULT_LIST_LIMIT = 500;

    boolean insert(AiClientModelConfig config);

    boolean updateById(AiClientModelConfig config);

    boolean updateByModelId(AiClientModelConfig config);

    boolean deleteById(Long id);

    boolean deleteByModelId(String modelId);

    Optional<AiClientModelConfig> findById(Long id);

    /** 返回至多一条 {@code modelId} 匹配配置；需要全部候选时使用 {@link #findAllByModelId(String)}。 */
    Optional<AiClientModelConfig> findByModelId(String modelId);

    /** 返回全部 {@code modelId} 匹配配置；{@code modelId} 不保证唯一。 */
    List<AiClientModelConfig> findAllByModelId(String modelId);

    List<AiClientModelConfig> findByApiId(String apiId);

    List<AiClientModelConfig> findByModelType(String modelType);

    /** 启用配置；默认最多 {@link #DEFAULT_LIST_LIMIT} 行，持久化层负责 LIMIT。 */
    default List<AiClientModelConfig> findEnabled() {
        return findEnabled(DEFAULT_LIST_LIMIT);
    }

    /** 启用配置（最多 {@code limit} 行）；{@code limit <= 0} 时取默认上限。 */
    List<AiClientModelConfig> findEnabled(int limit);

    /** 全量配置的默认分页入口；等价于 {@code findAll(0, DEFAULT_LIST_LIMIT)}。 */
    default List<AiClientModelConfig> findAll() {
        return findAll(0, DEFAULT_LIST_LIMIT);
    }

    /** 按 {@code create_time DESC, id DESC} 分页读取全部配置。实现必须把 LIMIT 下推到 SQL。 */
    List<AiClientModelConfig> findAll(int offset, int limit);
}
