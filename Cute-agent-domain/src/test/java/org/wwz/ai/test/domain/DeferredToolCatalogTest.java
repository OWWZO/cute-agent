package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpToolInfo;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.deferred.DeferredToolCatalog;
import org.wwz.ai.domain.agent.runtime.tool.deferred.DeferredToolEntry;

import java.util.List;
import java.util.Map;

public class DeferredToolCatalogTest {

    @Test
    public void searchesLocalAndMcpWithTheSameCatalog() {
        DeferredToolCatalog catalog = new DeferredToolCatalog(List.of(
                localEntry(),
                DeferredToolEntry.mcp(McpToolInfo.builder()
                        .name("mcp__github__create_issue")
                        .originalName("create_issue")
                        .serverKey("github")
                        .desc("create a GitHub issue")
                        .parameters("{\"type\":\"object\",\"required\":[\"title\"]}")
                        .build(), "github", Map.of("type", "object"), "")));

        Assert.assertEquals("document_generate", catalog.search("documents", 5).get(0).getName());
        Assert.assertEquals("document_generate", catalog.search("select:document_generate", 5).get(0).getName());
        Assert.assertEquals("local", catalog.search("select:document_generate", 5).get(0).getSource().name().toLowerCase());
        Assert.assertEquals("github", catalog.search("select:mcp__github__create_issue", 5).get(0).getSourceName());
    }

    @Test
    public void listingGroupsLocalCuteAndMcpServer() {
        DeferredToolCatalog catalog = new DeferredToolCatalog(List.of(
                localEntry(),
                DeferredToolEntry.mcp(McpToolInfo.builder()
                        .name("mcp__github__create_issue")
                        .serverKey("github")
                        .desc("create issue")
                        .parameters("{}")
                        .build(), "github", Map.of("type", "object"), "")));

        String listing = catalog.formatListingForToolSearchDescription();
        Assert.assertTrue(listing.contains("cute local tools"));
        Assert.assertTrue(listing.contains("github MCP tools"));
        Assert.assertFalse(listing.contains("properties"));
    }

    @Test
    public void catalogCollectionsAreImmutable() {
        DeferredToolCatalog catalog = new DeferredToolCatalog(List.of(
                localEntry()));
        Assert.assertThrows(UnsupportedOperationException.class, () -> catalog.listAll().clear());
        Assert.assertEquals(1, catalog.size());
    }

    /**
     * deferred 目录里的本地工具 schema 必须在执行期按本次 context 求值，
     * 不能缓存注册期（context 为 null）的快照。
     *
     * <p>browser / Agent 这类覆写了 {@code toParams(AgentContext)} 的工具一旦进入
     * deferred 名单，注册期快照会让 deferred 路径与主 {@code tools[]} 出现两份 schema。</p>
     */
    @Test
    public void localEntrySchemaMustBeResolvedWithCallerContext() {
        DeferredToolCatalog catalog = new DeferredToolCatalog(List.of(
                DeferredToolCatalog.localEntry(new ContextAwareTool(), "cute")));
        DeferredToolEntry entry = catalog.get("context_aware");
        Assert.assertNotNull(entry);

        AgentContext context = AgentContext.builder()
                .requestId("req-schema").sessionId("sess-schema").query("q").build();
        Assert.assertEquals("fresh", entry.getParameters(context).get("marker"));
        Assert.assertEquals("fresh", entry.getDescription(context));
    }

    /** 覆写 context-aware 方法的桩：无 context 时返回 stale，有 context 时返回 fresh。 */
    private static final class ContextAwareTool implements BaseTool {
        @Override
        public String getName() {
            return "context_aware";
        }

        @Override
        public String getDescription() {
            return "stale";
        }

        @Override
        public String getDescription(AgentContext context) {
            return context == null ? "stale" : "fresh";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("marker", "stale");
        }

        @Override
        public Map<String, Object> toParams(AgentContext context) {
            return Map.of("marker", context == null ? "stale" : "fresh");
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return "ok";
        }
    }

    private static final class LocalTool implements BaseTool {
        @Override
        public String getName() {
            return "document_generate";
        }

        @Override
        public String getDescription() {
            return "generate documents";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string")));
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return "ok";
        }
    }

    private static DeferredToolEntry localEntry() {
        return DeferredToolEntry.local(new LocalTool(), "cute", Map.of(
                "type", "object",
                "properties", Map.of("title", Map.of("type", "string"))), "");
    }
}
