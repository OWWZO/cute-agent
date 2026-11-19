package org.wwz.ai.domain.agent.runtime.artifact;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.File;

import java.util.List;
import java.util.Map;

public class ToolArtifactFileInfoTest {

    @Test
    public void internalArtifactsAreExcludedFromFrontendFileInfo() {
        ToolArtifactSource source = ToolArtifactSource.builder()
                .toolCallId("call-deep-search")
                .toolName("deep_search")
                .build();
        ToolArtifactBinding searchResult = ToolArtifactBinding.builder()
                .source(source)
                .file(File.builder()
                        .fileName("行业报告_search_result.txt")
                        .isInternalFile(true)
                        .build())
                .build();
        ToolArtifactBinding report = ToolArtifactBinding.builder()
                .source(source)
                .file(File.builder()
                        .fileName("行业报告.md")
                        .ossUrl("https://files.example.com/report.md")
                        .domainUrl("https://preview.example.com/report.md")
                        .isInternalFile(false)
                        .build())
                .build();

        List<Map<String, Object>> fileInfo = ToolArtifactFileInfo.fromBindings(
                List.of(searchResult, report));

        Assert.assertEquals(1, fileInfo.size());
        Assert.assertEquals("行业报告.md", fileInfo.get(0).get("fileName"));
    }
}
