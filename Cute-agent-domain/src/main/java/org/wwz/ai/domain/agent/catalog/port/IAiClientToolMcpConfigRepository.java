package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.AiClientToolMcpConfig;

import java.util.List;
import java.util.Optional;

/**
 * MCP 配置持久化端口。
 */
public interface IAiClientToolMcpConfigRepository {

    boolean insert(AiClientToolMcpConfig config);

    boolean updateById(AiClientToolMcpConfig config);

    boolean updateByMcpId(AiClientToolMcpConfig config);

    boolean deleteById(Long id);

    boolean deleteByMcpId(String mcpId);

    Optional<AiClientToolMcpConfig> findById(Long id);

    Optional<AiClientToolMcpConfig> findByMcpId(String mcpId);

    List<AiClientToolMcpConfig> findAll();

    List<AiClientToolMcpConfig> findByStatus(Integer status);

    List<AiClientToolMcpConfig> findByTransportType(String transportType);

    /**
     * 等价于 {@link #findByStatus(Integer) findByStatus(1)}：status 的启用值为 {@code 1}，
     * 定义在 Mapper SQL（{@code catalog_ai_client_tool_mcp_mapper.xml} 的 {@code queryEnabled}）中。
     */
    List<AiClientToolMcpConfig> findEnabled();
}
