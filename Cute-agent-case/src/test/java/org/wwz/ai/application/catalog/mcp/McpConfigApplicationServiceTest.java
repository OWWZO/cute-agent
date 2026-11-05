package org.wwz.ai.application.catalog.mcp;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.catalog.ApplicationResult;
import org.wwz.ai.domain.agent.catalog.model.AiClientToolMcpConfig;
import org.wwz.ai.domain.agent.catalog.model.McpRuntimeReloadResult;
import org.wwz.ai.domain.agent.catalog.port.IAiClientToolMcpConfigRepository;
import org.wwz.ai.domain.agent.catalog.port.IMcpRuntimeReloadPort;

import java.util.List;
import java.util.Optional;

public class McpConfigApplicationServiceTest {

    @Test
    public void shouldApplyTheSameDuplicateRuleToAdminMcpCreation() {
        Repository repository = new Repository();
        repository.existing = AiClientToolMcpConfig.builder().mcpId("mcp-1").build();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(McpRuntimeReloadResult::succeeded));

        ApplicationResult<Boolean> result = service.create(new McpConfigCommand(
                null, "mcp-1", "MCP", "streamable_http", "{}", 5, 1));

        Assert.assertEquals("0002", result.code());
        Assert.assertFalse(result.data());
        Assert.assertNull(repository.inserted);
    }

    @Test
    public void shouldReportReloadFailureAfterAdminWrite() {
        Repository repository = new Repository();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(() -> McpRuntimeReloadResult.failed("internal client error")));

        ApplicationResult<Boolean> result = service.create(new McpConfigCommand(
                null, "mcp-1", "MCP", "streamable_http", "{}", 5, 1));

        Assert.assertEquals("0000", result.code());
        Assert.assertTrue(result.data());
        Assert.assertEquals("MCP 配置已写入，但运行时刷新失败", result.info());
    }

    @Test
    public void shouldRejectNullAndBlankMcpIdBeforeInsert() {
        Repository repository = new Repository();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(McpRuntimeReloadResult::succeeded));

        ApplicationResult<Boolean> nullCommand = service.create(null);
        ApplicationResult<Boolean> blankId = service.create(new McpConfigCommand(
                null, " ", "MCP", "streamable_http", "{}", 5, 1));

        Assert.assertEquals("0002", nullCommand.code());
        Assert.assertEquals("0002", blankId.code());
        Assert.assertNull(repository.inserted);
    }

    @Test
    public void shouldTrimMcpIdOnCreate() {
        Repository repository = new Repository();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(McpRuntimeReloadResult::succeeded));

        ApplicationResult<Boolean> result = service.create(new McpConfigCommand(
                null, " mcp-1 ", "MCP", "streamable_http", "{}", 5, 1));

        Assert.assertEquals("0000", result.code());
        Assert.assertEquals("mcp-1", repository.inserted.getMcpId());
    }

    @Test
    public void shouldPreserveUnspecifiedFieldsOnPartialUpdateById() {
        Repository repository = new Repository();
        repository.existing = AiClientToolMcpConfig.builder()
                .id(7L)
                .mcpId("mcp-1")
                .mcpName("Original")
                .transportType("sse")
                .transportConfig("{\"url\":\"http://localhost\"}")
                .requestTimeout(42)
                .status(1)
                .build();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(McpRuntimeReloadResult::succeeded));

        ApplicationResult<Boolean> result = service.updateById(new McpConfigCommand(
                7L, null, null, null, null, null, 0));

        Assert.assertEquals("0000", result.code());
        Assert.assertEquals("mcp-1", repository.updatedById.getMcpId());
        Assert.assertEquals("Original", repository.updatedById.getMcpName());
        Assert.assertEquals("sse", repository.updatedById.getTransportType());
        Assert.assertEquals("{\"url\":\"http://localhost\"}", repository.updatedById.getTransportConfig());
        Assert.assertEquals(Integer.valueOf(42), repository.updatedById.getRequestTimeout());
        Assert.assertEquals(Integer.valueOf(0), repository.updatedById.getStatus());
    }

    @Test
    public void shouldPreserveUnspecifiedFieldsOnPartialUpdateByMcpId() {
        Repository repository = new Repository();
        repository.existing = AiClientToolMcpConfig.builder()
                .id(8L)
                .mcpId("mcp-2")
                .mcpName("Original")
                .transportType("stdio")
                .transportConfig("{\"command\":\"node\"}")
                .requestTimeout(60)
                .status(1)
                .build();
        McpConfigApplicationService service = new McpConfigApplicationService(repository,
                reloadSupport(McpRuntimeReloadResult::succeeded));

        ApplicationResult<Boolean> result = service.updateByMcpId(new McpConfigCommand(
                8L, "mcp-2", null, null, null, null, 0));

        Assert.assertEquals("0000", result.code());
        Assert.assertEquals("Original", repository.updatedByMcpId.getMcpName());
        Assert.assertEquals("stdio", repository.updatedByMcpId.getTransportType());
        Assert.assertEquals("{\"command\":\"node\"}", repository.updatedByMcpId.getTransportConfig());
        Assert.assertEquals(Integer.valueOf(60), repository.updatedByMcpId.getRequestTimeout());
        Assert.assertEquals(Integer.valueOf(0), repository.updatedByMcpId.getStatus());
    }

    private static McpWriteReloadSupport reloadSupport(IMcpRuntimeReloadPort port) {
        return new McpWriteReloadSupport(port);
    }

    private static final class Repository implements IAiClientToolMcpConfigRepository {
        private AiClientToolMcpConfig existing;
        private AiClientToolMcpConfig inserted;
        private AiClientToolMcpConfig updatedById;
        private AiClientToolMcpConfig updatedByMcpId;

        @Override public boolean insert(AiClientToolMcpConfig config) { inserted = config; return true; }
        @Override public boolean updateById(AiClientToolMcpConfig config) { updatedById = config; return true; }
        @Override public boolean updateByMcpId(AiClientToolMcpConfig config) { updatedByMcpId = config; return true; }
        @Override public boolean deleteById(Long id) { return true; }
        @Override public boolean deleteByMcpId(String mcpId) { return true; }
        @Override public Optional<AiClientToolMcpConfig> findById(Long id) {
            return existing != null && id.equals(existing.getId()) ? Optional.of(existing) : Optional.empty();
        }
        @Override public Optional<AiClientToolMcpConfig> findByMcpId(String mcpId) { return Optional.ofNullable(existing); }
        @Override public List<AiClientToolMcpConfig> findAll() { return List.of(); }
        @Override public List<AiClientToolMcpConfig> findByStatus(Integer status) { return List.of(); }
        @Override public List<AiClientToolMcpConfig> findByTransportType(String transportType) { return List.of(); }
        @Override public List<AiClientToolMcpConfig> findEnabled() { return List.of(); }
    }
}
