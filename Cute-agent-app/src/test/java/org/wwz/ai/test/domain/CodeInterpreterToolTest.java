package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.CodeInterpreterResponse;
import org.wwz.ai.domain.agent.runtime.tool.common.CodeInterpreterTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CodeInterpreterToolTest {

    @Test
    public void shouldAppendUploadedArtifactUrlToLlmObservation() {
        CodeInterpreterTool tool = new CodeInterpreterTool();
        String observation = ReflectionTestUtils.invokeMethod(
                tool,
                "appendArtifactUrls",
                "图表已生成",
                List.of(CodeInterpreterResponse.FileInfo.builder()
                        .fileName("chart.png")
                        .domainUrl("https://file.example.com/preview/chart.png")
                        .ossUrl("https://file.example.com/download/chart.png")
                        .build())
        );

        Assert.assertTrue(observation.contains("chart.png"));
        Assert.assertTrue(observation.contains("https://file.example.com/preview/chart.png"));
    }

    @Test
    public void shouldListTopLevelWorkspaceFileNamesInOrder() throws Exception {
        Path workspace = Files.createTempDirectory("cute-code-interpreter-list");
        Files.writeString(workspace.resolve("b.csv"), "b");
        Files.writeString(workspace.resolve("a.xlsx"), "a");
        Files.createDirectories(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("nested/deep.csv"), "deep");

        Assert.assertEquals(
                List.of("a.xlsx", "b.csv"),
                ReflectionTestUtils.invokeMethod(
                        new CodeInterpreterTool(),
                        "listWorkspaceTopLevelFileNames",
                        AgentContext.builder().workspaceRoot(workspace.toString()).build()
                )
        );
    }

    @Test
    public void shouldReturnEmptyListWhenWorkspaceRootMissingOrNotADirectory() {
        // workspaceRoot 未配置 / 目录不存在都是降级路径：返回空列表，不抛异常
        Assert.assertEquals(
                List.of(),
                ReflectionTestUtils.invokeMethod(
                        new CodeInterpreterTool(),
                        "listWorkspaceTopLevelFileNames",
                        AgentContext.builder().build()
                )
        );
        Assert.assertEquals(
                List.of(),
                ReflectionTestUtils.invokeMethod(
                        new CodeInterpreterTool(),
                        "listWorkspaceTopLevelFileNames",
                        AgentContext.builder().workspaceRoot("/cute-agent-not-exists-dir").build()
                )
        );
    }
}
