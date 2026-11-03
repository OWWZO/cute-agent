package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.planmode.PlanArtifactStore;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePathGuard;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 计划落盘符号链接回归：修复前 {@code .cute} 或 {@code plan.md} 是软链时，
 * plan mode 会把内容写到工作区外的任意文件。
 */
public class PlanArtifactStoreSecurityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private PlanArtifactStore storeFor(Path root) {
        WorkspaceRuntimeOptions options = WorkspaceRuntimeOptions.builder()
                .enabled(true)
                .rootTemplate(root.toAbsolutePath().normalize() + "/{sessionId}")
                .build();
        WorkspaceService workspaceService = new WorkspaceService(
                options, new WorkspacePathGuard(), null, null);
        return new PlanArtifactStore(workspaceService);
    }

    @Test
    public void writesAndReadsPlanInsideWorkspace() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        PlanArtifactStore store = storeFor(root);

        Assert.assertTrue(store.writePlan("sess-1", "# plan").isPresent());
        Assert.assertEquals("# plan", store.readPlan("sess-1").orElse(null));
        Assert.assertTrue(Files.isRegularFile(root.resolve("sess-1/.cute/plan.md")));
    }

    @Test
    public void rejectsWhenCuteDirectoryIsSymlink() throws Exception {
        Path root = folder.newFolder("ws-link-dir").toPath();
        Path outside = folder.newFolder("outside-dir").toPath();
        Files.createDirectories(root.resolve("sess-1"));
        assumeSymlink(root.resolve("sess-1/.cute"), outside);

        PlanArtifactStore store = storeFor(root);
        Assert.assertFalse("软链目录必须拒绝写入", store.writePlan("sess-1", "pwned").isPresent());
        Assert.assertFalse("不得在工作区外落盘", Files.exists(outside.resolve("plan.md")));
    }

    @Test
    public void rejectsWhenPlanFileIsSymlink() throws Exception {
        Path root = folder.newFolder("ws-link-file").toPath();
        Path outside = folder.newFolder("outside-file").toPath();
        Path target = outside.resolve("victim.md");
        Files.writeString(target, "original", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("sess-1/.cute"));
        assumeSymlink(root.resolve("sess-1/.cute/plan.md"), target);

        PlanArtifactStore store = storeFor(root);
        Assert.assertFalse("软链文件必须拒绝写入", store.writePlan("sess-1", "pwned").isPresent());
        Assert.assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
        Assert.assertFalse(store.readPlan("sess-1").isPresent());
    }

    private void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assume.assumeNoException("当前环境不支持创建符号链接，跳过软链用例", e);
        }
    }
}
