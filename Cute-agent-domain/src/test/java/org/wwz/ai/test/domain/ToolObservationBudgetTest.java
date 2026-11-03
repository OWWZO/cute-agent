package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationBudget;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 预算层契约：超限落盘并给可续读信封；落盘失败退回截断，绝不丢内容。
 */
public class ToolObservationBudgetTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void oversizedOutputIsPersistedAsEnvelope() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String body = "x".repeat(40_000);

        String result = ToolObservationBudget.apply(context, "workspace_grep", body);

        Assert.assertTrue(result.contains("<persisted-output>"));
        Assert.assertTrue(result.contains("</persisted-output>"));
        Assert.assertTrue(result.contains(".cute/observations/"));
        Assert.assertTrue(result.contains("workspace_grep-"));
        Assert.assertTrue("信封长度应远小于原文", result.length() < 3_000);

        try (Stream<Path> files = Files.list(root.resolve(".cute/observations"))) {
            Path saved = files.findFirst().orElseThrow();
            Assert.assertEquals(body, Files.readString(saved, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void withinBudgetPassesThrough() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String body = "y".repeat(100);

        Assert.assertEquals(body, ToolObservationBudget.apply(context, "workspace_grep", body));
    }

    @Test
    public void unregisteredToolUsesDefaultCap() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String body = "z".repeat(ToolObservationBudget.DEFAULT_CAP + 5_000);

        String result = ToolObservationBudget.apply(context, "some_other_tool", body);

        // 未登记投影器的工具也必须落盘：它们只有全局 maxObserve 兜底，单次几十 KB 会反复重放进上下文。
        Assert.assertTrue(result.contains("<persisted-output>"));
        Assert.assertTrue(result.contains("some_other_tool-"));
    }

    @Test
    public void missingWorkspaceFallsBackToTruncation() {
        AgentContext context = AgentContext.builder().build();
        String body = "q".repeat(40_000);

        String result = ToolObservationBudget.apply(context, "workspace_grep", body);

        Assert.assertTrue(result.endsWith(ToolObservationSerializer.TRUNCATION_NOTICE));
        Assert.assertEquals(ToolObservationBudget.GREP_GLOB_LIST_CAP, result.length());
    }

    @Test
    public void concurrentPersistDoesNotOverwrite() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String first = "a".repeat(40_000);
        String second = "b".repeat(40_000);

        String firstResult = ToolObservationBudget.apply(context, "workspace_grep", first);
        String secondResult = ToolObservationBudget.apply(context, "workspace_grep", second);

        Assert.assertNotEquals(firstResult, secondResult);
        Path dir = root.resolve(".cute/observations");
        try (Stream<Path> files = Files.list(dir)) {
            Assert.assertEquals(2, files.count());
        }
        // 信封里给的是工作区相对路径（模型正是拿它去 workspace_read），所以从 root 解析。
        Assert.assertTrue(Files.readString(root.resolve(pathOf(firstResult)), StandardCharsets.UTF_8)
                .startsWith("aaa"));
        Assert.assertTrue(Files.readString(root.resolve(pathOf(secondResult)), StandardCharsets.UTF_8)
                .startsWith("bbb"));
    }

    private static Path pathOf(String envelope) {
        int start = envelope.indexOf("saved to: ") + "saved to: ".length();
        return Path.of(envelope.substring(start, envelope.indexOf('\n', start)));
    }

    @Test
    public void readHasLargerBudgetThanGrep() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String body = "r".repeat(25_000);

        Assert.assertEquals(body, ToolObservationBudget.apply(context, "workspace_read", body));
    }

    @Test
    public void truncationNoticeTellsModelHowToContinue() {
        Assert.assertTrue(ToolObservationSerializer.TRUNCATION_NOTICE.contains("workspace_read(start_line, line_count)"));
    }

    @Test
    public void maliciousToolNameCannotEscapeObservationDir() throws Exception {
        Path root = folder.newFolder("ws-escape").toPath();
        AgentContext context = AgentContext.builder().workspaceRoot(root.toString()).build();
        String body = "e".repeat(40_000);

        String result = ToolObservationBudget.apply(context, "../../escape", body);

        Assert.assertTrue(result.contains("<persisted-output>"));
        Assert.assertFalse("信封不得包含穿越路径", result.contains("../../"));
        Path dir = root.resolve(".cute/observations");
        try (Stream<Path> files = Files.list(dir)) {
            Path saved = files.findFirst().orElseThrow();
            Assert.assertEquals(dir.normalize(), saved.getParent().normalize());
            Assert.assertTrue("文件名应来自清洗后的工具名", saved.getFileName().toString().startsWith("escape-"));
        }
        // 工作区之外不应出现以工具名命名的文件。
        Assert.assertFalse(Files.exists(root.getParent().resolve("escape.txt")));
        Assert.assertFalse(Files.exists(root.getParent().getParent().resolve("escape.txt")));
    }
}
