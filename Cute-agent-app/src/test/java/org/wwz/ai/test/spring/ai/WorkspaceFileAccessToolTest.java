package org.wwz.ai.test.spring.ai;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceGlobTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceGrepTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceListTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePathGuard;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceReadTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceWriteTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 会话工作区文件工具测试。
 */
public class WorkspaceFileAccessToolTest {

    private Path workspaceRoot;
    private WorkspaceService workspaceService;
    private WorkspaceRuntimeOptions options;
    private AgentContext agentContext;

    @Before
    public void setUp() throws Exception {
        workspaceRoot = Files.createTempDirectory("cute-workspace-tool-test");
        options = WorkspaceRuntimeOptions.builder()
                .enabled(true)
                .rootTemplate(workspaceRoot.toString())
                .maxReadChars(4000)
                .maxListEntries(50)
                .maxGlobResults(20)
                .maxGrepMatches(20)
                .maxWriteChars(10000)
                .build();
        workspaceService = new WorkspaceService(options, new WorkspacePathGuard(), null, new org.wwz.ai.domain.agent.runtime.tool.skill.SkillVirtualPaths(org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions.builder().enabled(false).build()));
        agentContext = AgentContext.builder()
                .requestId("req-ws-001")
                .sessionId("session-ws-001")
                .workspaceRoot(workspaceRoot.toString())
                .build();

        Files.writeString(workspaceRoot.resolve("notes.md"), "hello workspace\nline2 gross_margin\n", StandardCharsets.UTF_8);
        Files.createDirectories(workspaceRoot.resolve("docs"));
        Files.writeString(workspaceRoot.resolve("docs/readme.md"), "# readme\n", StandardCharsets.UTF_8);
    }

    @Test
    public void shouldReadAndWriteWithinWorkspace() {
        WorkspaceReadTool readTool = new WorkspaceReadTool(workspaceService, options);
        WorkspaceWriteTool writeTool = new WorkspaceWriteTool(workspaceService, options);

        String writeResult = String.valueOf(writeTool.execute(Map.of(
                "path", "out/report.md",
                "content", "generated content"
        ), agentContext));
        Assert.assertTrue(writeResult.contains("已写入文件"));

        String readResult = String.valueOf(readTool.execute(Map.of(
                "path", "notes.md",
                "start_line", 1,
                "line_count", 10
        ), agentContext));
        Assert.assertTrue(readResult.contains("1 | hello workspace"));
    }

    @Test
    public void shouldListGlobAndGrepWithinWorkspace() {
        WorkspaceListTool listTool = new WorkspaceListTool(workspaceService, options);
        WorkspaceGlobTool globTool = new WorkspaceGlobTool(workspaceService, options);
        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(workspaceService, options);

        Assert.assertTrue(String.valueOf(listTool.execute(Map.of("path", workspaceRoot.toString(), "max_depth", 2), agentContext))
                .contains("notes.md"));
        Assert.assertTrue(String.valueOf(globTool.execute(Map.of(
                "path", workspaceRoot.toString(),
                "pattern", "**/*.md"
        ), agentContext)).contains("docs/readme.md"));
        Assert.assertTrue(String.valueOf(grepTool.execute(Map.of(
                "path", workspaceRoot.toString(),
                "pattern", "gross_margin"
        ), agentContext)).contains("notes.md"));
    }

    @Test
    public void shouldRejectPathsOutsideWorkspace() throws Exception {
        Path outside = Files.createTempFile("outside-workspace", ".md");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);

        WorkspaceReadTool readTool = new WorkspaceReadTool(workspaceService, options);
        WorkspaceWriteTool writeTool = new WorkspaceWriteTool(workspaceService, options);

        Assert.assertTrue(String.valueOf(readTool.execute(Map.of("path", outside.toString()), agentContext))
                .contains("outside allowed roots"));
        Assert.assertTrue(String.valueOf(writeTool.execute(Map.of(
                "path", outside.toString(),
                "content", "x"
        ), agentContext)).contains("outside allowed roots"));
    }

    @Test
    public void shouldBoundGlobAndGrepTraversalEvenWithoutMatches() throws Exception {
        options.setMaxGlobResults(1);
        options.setMaxGrepMatches(1);
        Files.createDirectories(workspaceRoot.resolve("a/b"));
        Files.writeString(workspaceRoot.resolve("a/one.txt"), "nothing\n", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("a/b/two.txt"), "nothing\n", StandardCharsets.UTF_8);

        WorkspaceGlobTool globTool = new WorkspaceGlobTool(workspaceService, options);
        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(workspaceService, options);

        Assert.assertTrue(String.valueOf(globTool.execute(Map.of(
                "path", workspaceRoot.toString(), "pattern", "**/*.java"), agentContext)).contains("truncated"));
        Assert.assertTrue(String.valueOf(grepTool.execute(Map.of(
                "path", workspaceRoot.toString(), "pattern", "missing"), agentContext)).contains("truncated"));
    }

    @Test
    public void shouldBoundReadMemoryForAnOversizedLine() throws Exception {
        options.setMaxReadChars(16);
        Files.writeString(workspaceRoot.resolve("large.txt"), "0123456789abcdefghijklmnopqrstuvwxyz\nsecond\n",
                StandardCharsets.UTF_8);

        WorkspaceReadTool readTool = new WorkspaceReadTool(workspaceService, options);
        String result = String.valueOf(readTool.execute(Map.of(
                "path", "large.txt", "start_line", 1, "line_count", 1), agentContext));

        Assert.assertTrue(result.contains("truncated"));
        Assert.assertTrue(result.contains("1 | 0123456789"));
        Assert.assertFalse(result.contains("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    public void shouldReturnStructuredErrorsForWorkspaceArgumentMistakes() {
        WorkspaceReadTool readTool = new WorkspaceReadTool(workspaceService, options);
        WorkspaceGlobTool globTool = new WorkspaceGlobTool(workspaceService, options);
        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(workspaceService, options);

        ToolResultPayload readDir = (ToolResultPayload) readTool.execute(
                Map.of("path", "docs"), agentContext);
        Map<?, ?> readDetail = (Map<?, ?>) readDir.getLlmData();
        Assert.assertEquals("WORKSPACE_READ_PATH_IS_DIR", readDetail.get("errorCode"));
        Assert.assertEquals("Use workspace_list(path=...) to inspect directories.", readDetail.get("suggestion"));

        ToolResultPayload globFile = (ToolResultPayload) globTool.execute(
                Map.of("path", "notes.md", "pattern", "*.md"), agentContext);
        Map<?, ?> globDetail = (Map<?, ?>) globFile.getLlmData();
        Assert.assertEquals("WORKSPACE_GLOB_PATH_NOT_DIR", globDetail.get("errorCode"));
        Assert.assertEquals("Use workspace_read(path=...) for files; workspace_glob path must be a directory.",
                globDetail.get("suggestion"));

        ToolResultPayload grepMissingPattern = (ToolResultPayload) grepTool.execute(
                Map.of("path", "notes.md"), agentContext);
        Map<?, ?> grepDetail = (Map<?, ?>) grepMissingPattern.getLlmData();
        Assert.assertEquals("WORKSPACE_GREP_PATTERN_REQUIRED", grepDetail.get("errorCode"));
        Assert.assertNotNull(grepDetail.get("suggestion"));
    }

    @Test
    public void workspaceDescriptionsIncludeUsageExamples() {
        Assert.assertTrue(new WorkspaceReadTool(workspaceService, options).getDescription()
                .contains("workspace_read(path=\"src/a.py\")"));
        Assert.assertTrue(new WorkspaceGlobTool(workspaceService, options).getDescription()
                .contains("workspace_glob(path=\"src\", pattern=\"*.py\")"));
    }
}
