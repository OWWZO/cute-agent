package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.artifact.TaskSummaryArtifactProtocol;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactBinding;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.TaskSummaryResult;

import java.util.List;
import java.util.Map;

public class TaskSummaryArtifactProtocolTest {

    @Test
    public void shouldKeepRawFinalAnswerWithoutAttachingVisibleFiles() {
        ToolArtifactBinding binding = ToolArtifactBinding.builder()
                .source(ToolArtifactSource.builder()
                        .toolCallId("child-call-1")
                        .toolName("workspace_write")
                        .build())
                .file(org.wwz.ai.domain.agent.runtime.dto.File.builder()
                        .fileName("child-report.md")
                        .domainUrl("https://example.com/preview/child-report.md")
                        .ossUrl("https://example.com/download/child-report.md")
                        .isInternalFile(false)
                        .build())
                .build();

        TaskSummaryResult result = TaskSummaryArtifactProtocol.resolveForDelivery(
                "子 Agent 已完成",
                List.of(binding)
        );
        Map<String, Object> payload = TaskSummaryArtifactProtocol.toEventPayload(result);

        Assert.assertEquals("子 Agent 已完成", payload.get("taskSummary"));
        Assert.assertNull(payload.get("fileList"));
        Assert.assertNull(payload.get("artifactKeys"));
        Assert.assertTrue(result.getFiles() == null || result.getFiles().isEmpty());
    }

    @Test
    public void shouldKeepDelimiterAndKeysWithoutMatchingBindings() {
        ToolArtifactBinding report = ToolArtifactBinding.builder()
                .source(ToolArtifactSource.builder()
                        .toolCallId("child-call-1")
                        .toolName("workspace_write")
                        .build())
                .file(org.wwz.ai.domain.agent.runtime.dto.File.builder()
                        .fileName("child-report.md")
                        .domainUrl("https://example.com/preview/child-report.md")
                        .ossUrl("https://example.com/download/child-report.md")
                        .isInternalFile(false)
                        .build())
                .build();
        ToolArtifactBinding style = ToolArtifactBinding.builder()
                .source(ToolArtifactSource.builder()
                        .toolCallId("child-call-2")
                        .toolName("workspace_write")
                        .build())
                .file(org.wwz.ai.domain.agent.runtime.dto.File.builder()
                        .fileName("style.css")
                        .domainUrl("https://example.com/preview/style.css")
                        .ossUrl("https://example.com/download/style.css")
                        .isInternalFile(false)
                        .build())
                .build();

        TaskSummaryResult result = TaskSummaryArtifactProtocol.resolveForDelivery(
                "报告已完成。$$$ child-report.md",
                List.of(report, style)
        );
        Map<String, Object> payload = TaskSummaryArtifactProtocol.toEventPayload(
                result,
                List.of(report, style)
        );

        Assert.assertEquals("报告已完成。$$$ child-report.md", payload.get("taskSummary"));
        Assert.assertEquals(List.of("child-report.md"), payload.get("artifactKeys"));
        Assert.assertNull(payload.get("fileList"));
        Assert.assertTrue(result.getFiles() == null || result.getFiles().isEmpty());
    }

    @Test
    public void shouldExtractRelativePathKeysWithoutSelectingFiles() {
        TaskSummaryResult named = TaskSummaryArtifactProtocol.parse(
                "页面已生成。$$$ site/index.html、site/css/style.css"
        );
        Assert.assertEquals("页面已生成。$$$ site/index.html、site/css/style.css", named.getTaskSummary());
        Assert.assertEquals(List.of("site/index.html", "site/css/style.css"), named.getArtifactKeys());
        Assert.assertTrue(named.getFiles() == null || named.getFiles().isEmpty());
    }

    @Test
    public void shouldIgnoreInlineDelimiterMentionAndUseTrailingArtifactSection() {
        String response = """
                你之前看不到 `screens/` 的原因：上一轮会话结束时工作区被重置。
                我上一条 `$$$` 里写的 `test-page/screens/*.png` 在当时已失效。

                当前真实结构：

                $$$
                test-page/index.html
                test-page/screens/desktop.png
                test-page/screens/mobile.png
                test-page/README.md
                """;

        TaskSummaryResult result = TaskSummaryArtifactProtocol.parse(response);

        Assert.assertEquals(response.stripTrailing(), result.getTaskSummary());
        Assert.assertEquals(
                List.of(
                        "test-page/index.html",
                        "test-page/screens/desktop.png",
                        "test-page/screens/mobile.png",
                        "test-page/README.md"
                ),
                result.getArtifactKeys()
        );
    }

    @Test
    public void shouldNotTreatProseDelimiterMentionAsArtifactSection() {
        String response = """
                我上一条 `$$$` 里写的 `test-page/screens/*.png` 在当时已失效，
                这是我未在交付前重新核对路径造成的，抱歉。

                **当前真实结构**：
                test-page/
                ├── index.html          33 KB   交付物，单文件自包含
                └── screens/
                    └── desktop.png     271 KB
                """;

        TaskSummaryResult result = TaskSummaryArtifactProtocol.parse(response);

        Assert.assertEquals(response.stripTrailing(), result.getTaskSummary());
        Assert.assertTrue(result.getArtifactKeys() == null || result.getArtifactKeys().isEmpty());
    }

    @Test
    public void shouldNotTreatTrailingInlineDelimiterAsArtifactSection() {
        TaskSummaryResult result = TaskSummaryArtifactProtocol.parse("文件清单分隔符写作 $$$");

        Assert.assertEquals("文件清单分隔符写作 $$$", result.getTaskSummary());
        Assert.assertTrue(result.getArtifactKeys() == null || result.getArtifactKeys().isEmpty());
    }
}
