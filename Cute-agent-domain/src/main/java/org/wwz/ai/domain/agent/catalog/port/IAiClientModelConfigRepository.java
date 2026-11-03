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

    boolean insert(AiClientModelConfig config);

    boolean updateById(AiClientModelConfig config);

    boolean updateByModelId(AiClientModelConfig config);

    boolean deleteById(Long id);

    boolean deleteByModelId(String modelId);

    Optional<AiClientModelConfig> findById(Long id);

    Optional<AiClientModelConfig> findByModelId(String modelId);

    List<AiClientModelConfig> findAllByModelId(String modelId);

    List<AiClientModelConfig> findByApiId(String apiId);

    List<AiClientModelConfig> findByModelType(String modelType);

    List<AiClientModelConfig> findEnabled();

    List<AiClientModelConfig> findAll();
}
