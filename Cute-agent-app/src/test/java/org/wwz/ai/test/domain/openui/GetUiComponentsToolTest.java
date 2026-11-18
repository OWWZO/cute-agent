package org.wwz.ai.test.domain.openui;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.openui.GetUiComponentsTool;
import org.wwz.ai.domain.agent.runtime.tool.common.openui.OpenUiCatalogLoadStateStore;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * get_ui_components 只读目录工具：返回扩展组件文本，且不重复收录常驻组件。
 */
public class GetUiComponentsToolTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void shouldExposeEmptyParameterSchema() {
        GetUiComponentsTool tool = new GetUiComponentsTool();

        Assert.assertEquals("get_ui_components", tool.getName());

        Map<?, ?> parameters = (Map<?, ?>) tool.toParams();
        Assert.assertEquals("object", parameters.get("type"));
        Assert.assertEquals(List.of(), parameters.get("required"));
        Assert.assertTrue(((Map<?, ?>) parameters.get("properties")).isEmpty());
    }

    @Test
    public void shouldReturnExtendedCatalogAsPlainText() {
        ToolResultPayload result = (ToolResultPayload) new GetUiComponentsTool().execute(Map.of(), null);

        Assert.assertFalse(result.getFailed());
        Assert.assertTrue(result.getLlmData() instanceof String);
        String catalog = (String) result.getLlmData();

        Assert.assertTrue(catalog.contains("Map("));
        Assert.assertTrue(catalog.contains("Svg("));
        Assert.assertTrue(catalog.contains("HtmlView("));
        // 常驻组件不应在扩展目录里重复出现（避免模型看到两份签名）。
        Assert.assertFalse(catalog.contains("Col(children"));
        Assert.assertFalse(catalog.contains("Title(text"));
    }

    @Test
    public void shouldReturnFullCatalogWhenContextIsNull() {
        ToolResultPayload result = (ToolResultPayload) new GetUiComponentsTool().execute(Map.of(), null);

        String catalog = (String) result.getLlmData();
        Assert.assertEquals(catalog, result.getToolResult());
        Assert.assertEquals(catalog, result.getLlmObservation());
    }

    @Test
    public void shouldStubOnlyLlmObservationOnRepeatedCallInSameContext() throws Exception {
        AgentContext context = newContext("req-repeat", null);
        GetUiComponentsTool tool = new GetUiComponentsTool(new OpenUiCatalogLoadStateStore());

        ToolResultPayload first = (ToolResultPayload) tool.execute(Map.of(), context);
        ToolResultPayload second = (ToolResultPayload) tool.execute(Map.of(), context);

        String catalog = (String) first.getLlmData();
        Assert.assertEquals(catalog, first.getLlmObservation());
        Assert.assertEquals(catalog, second.getLlmData());
        Assert.assertEquals(catalog, second.getToolResult());
        Assert.assertEquals(GetUiComponentsTool.ALREADY_LOADED_OBSERVATION, second.getLlmObservation());
        Assert.assertTrue(context.getLoadedOpenUiCatalogVersion() != null);
        Assert.assertTrue(Files.isRegularFile(workspaceRoot().resolve(".cute/openui-catalog-loaded.json")));
    }

    @Test
    public void shouldHydrateSidecarOnNextTurn() throws Exception {
        AgentContext firstTurn = newContext("req-turn-1", null);
        new GetUiComponentsTool(new OpenUiCatalogLoadStateStore()).execute(Map.of(), firstTurn);

        AgentContext nextTurn = newContext("req-turn-2", null);
        new OpenUiCatalogLoadStateStore().hydrate(nextTurn);
        ToolResultPayload result = (ToolResultPayload) new GetUiComponentsTool(
                new OpenUiCatalogLoadStateStore()).execute(Map.of(), nextTurn);

        Assert.assertEquals(GetUiComponentsTool.ALREADY_LOADED_OBSERVATION, result.getLlmObservation());
        Assert.assertTrue(((String) result.getLlmData()).contains("Map("));
    }

    @Test
    public void shouldReturnFullCatalogAfterStateCleared() throws Exception {
        OpenUiCatalogLoadStateStore store = new OpenUiCatalogLoadStateStore();
        AgentContext context = newContext("req-clear", null);
        GetUiComponentsTool tool = new GetUiComponentsTool(store);
        tool.execute(Map.of(), context);

        store.clear(context);
        Assert.assertNull(context.getLoadedOpenUiCatalogVersion());
        Assert.assertFalse(Files.exists(workspaceRoot().resolve(".cute/openui-catalog-loaded.json")));

        ToolResultPayload result = (ToolResultPayload) tool.execute(Map.of(), context);

        Assert.assertEquals(result.getLlmData(), result.getLlmObservation());
        Assert.assertTrue(context.getLoadedOpenUiCatalogVersion() != null);
    }

    @Test
    public void subAgentShouldNotReuseMainCatalogState() throws Exception {
        OpenUiCatalogLoadStateStore store = new OpenUiCatalogLoadStateStore();
        AgentContext parent = newContext("req-parent", "session-openui");
        new GetUiComponentsTool(store).execute(Map.of(), parent);

        AgentContext child = SubAgentContextFactory.create(
                parent, "prompt", "desc", new ToolCollection(), "openui-child",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE);
        Assert.assertNull(child.getLoadedOpenUiCatalogVersion());
        store.hydrate(child);

        ToolResultPayload childResult = (ToolResultPayload) new GetUiComponentsTool(store)
                .execute(Map.of(), child);
        Assert.assertEquals(childResult.getLlmData(), childResult.getLlmObservation());
        Assert.assertTrue(Files.isRegularFile(workspaceRoot().resolve(".cute/openui-catalog-loaded.json")));

        store.clear(child);
        Assert.assertTrue("子 Agent 不得删除主会话 sidecar",
                Files.isRegularFile(workspaceRoot().resolve(".cute/openui-catalog-loaded.json")));
    }

    private AgentContext newContext(String requestId, String sessionId) throws Exception {
        return AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .workspaceRoot(workspaceRoot().toString())
                .build();
    }

    private Path workspaceRoot() throws Exception {
        return folder.getRoot().toPath();
    }
}
