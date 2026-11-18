package org.wwz.ai.domain.agent.runtime.tool.common.openui;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class OpenUiCatalogLoadStateStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void persistThenHydrateRestoresVersion() throws Exception {
        Path workspace = folder.newFolder("roundtrip").toPath();
        OpenUiCatalogLoadStateStore store = new OpenUiCatalogLoadStateStore();
        AgentContext first = context(workspace, "req-1", null);
        store.persist(first, "v1");

        AgentContext second = context(workspace, "req-2", null);
        store.hydrate(second);

        Assert.assertEquals("v1", second.getLoadedOpenUiCatalogVersion());
    }

    @Test
    public void clearRemovesPersistedState() throws Exception {
        Path workspace = folder.newFolder("clear").toPath();
        OpenUiCatalogLoadStateStore store = new OpenUiCatalogLoadStateStore();
        AgentContext context = context(workspace, "req-clear", null);
        store.persist(context, "v1");

        store.clear(context);

        Assert.assertNull(context.getLoadedOpenUiCatalogVersion());
        Assert.assertFalse(Files.exists(storeFile(workspace)));
    }

    @Test
    public void brokenJsonDoesNotThrow() throws Exception {
        Path workspace = folder.newFolder("broken").toPath();
        Files.createDirectories(storeFile(workspace).getParent());
        Files.writeString(storeFile(workspace), "{not-json", StandardCharsets.UTF_8);
        AgentContext context = context(workspace, "req-broken", null);

        new OpenUiCatalogLoadStateStore().hydrate(context);

        Assert.assertNull(context.getLoadedOpenUiCatalogVersion());
    }

    @Test
    public void blankWorkspaceDoesNotWriteFile() {
        AgentContext context = AgentContext.builder().requestId("req-blank").build();

        new OpenUiCatalogLoadStateStore().persist(context, "v1");

        Assert.assertNull(context.getLoadedOpenUiCatalogVersion());
    }

    @Test
    public void subAgentDoesNotReadWriteOrClearMainState() throws Exception {
        Path workspace = folder.newFolder("subagent").toPath();
        OpenUiCatalogLoadStateStore store = new OpenUiCatalogLoadStateStore();
        AgentContext parent = context(workspace, "req-parent", null);
        store.persist(parent, "main-v1");

        AgentContext child = SubAgentContextFactory.create(
                parent, "prompt", "desc", new ToolCollection(), "catalog-child",
                SubAgentRegistry.TYPE_GENERAL_PURPOSE);
        store.hydrate(child);
        store.persist(child, "child-v1");
        Assert.assertNull(child.getLoadedOpenUiCatalogVersion());

        String persistedAfterChild = Files.readString(storeFile(workspace), StandardCharsets.UTF_8);
        Assert.assertTrue(persistedAfterChild.contains("main-v1"));

        child.setLoadedOpenUiCatalogVersion("child-v1");
        store.clear(child);
        Assert.assertNull(child.getLoadedOpenUiCatalogVersion());
        Assert.assertTrue(Files.isRegularFile(storeFile(workspace)));
    }

    private AgentContext context(Path workspace, String requestId, String version) {
        return AgentContext.builder()
                .requestId(requestId)
                .sessionId("session-catalog")
                .workspaceRoot(workspace.toString())
                .loadedOpenUiCatalogVersion(version)
                .build();
    }

    private Path storeFile(Path workspace) {
        return workspace.resolve(OpenUiCatalogLoadStateStore.RELATIVE_STORE_PATH);
    }
}
