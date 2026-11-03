package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.AiClientApiConfig;

import java.util.List;
import java.util.Optional;

/**
 * API 配置持久化端口。
 * <p>写方法返回 {@code false} 表示未影响任何行（目标行不存在或已删除）；持久化/基础设施错误必须抛出，
 * 不得吞掉后返回 {@code false}。{@code apiId} 是唯一业务键（DB 唯一索引），调用方须传入非空非空白值。</p>
 */
public interface IAiClientApiConfigRepository {

    boolean insert(AiClientApiConfig config);

    boolean updateById(AiClientApiConfig config);

    boolean updateByApiId(AiClientApiConfig config);

    boolean deleteById(Long id);

    boolean deleteByApiId(String apiId);

    Optional<AiClientApiConfig> findById(Long id);

    Optional<AiClientApiConfig> findByApiId(String apiId);

    List<AiClientApiConfig> findEnabled();

    List<AiClientApiConfig> findAll();
}
