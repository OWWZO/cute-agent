package org.wwz.ai.test.domain.artifact;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactRegistry;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;

/**
 * 产物去重必须包含工作区相对路径：同名且无 URL 的不同文件不能被吞掉。
 */
public class ToolArtifactRegistryDedupeTest {

    @Test
    public void sameFileNameWithDifferentRelativePathAreDistinctBindings() {
        ToolArtifactRegistry registry = new ToolArtifactRegistry();
        ToolArtifactSource source = ToolArtifactSource.builder()
                .sessionId("s1")
                .requestId("req-1")
                .toolCallId("call-1")
                .toolName("workspace_write")
                .build();

        registry.registerGeneratedFile(source, File.builder()
                .fileName("index.html")
                .relativePath("report/index.html")
                .build());
        registry.registerGeneratedFile(source, File.builder()
                .fileName("index.html")
                .relativePath("site/index.html")
                .build());

        Assert.assertEquals(2, registry.findBindingsByToolCallId("call-1").size());
    }

    @Test
    public void identicalBindingIsStillDeduplicated() {
        ToolArtifactRegistry registry = new ToolArtifactRegistry();
        ToolArtifactSource source = ToolArtifactSource.builder()
                .toolCallId("call-2")
                .toolName("workspace_write")
                .build();
        File file = File.builder().fileName("index.html").relativePath("report/index.html").build();

        registry.registerGeneratedFile(source, file);
        registry.registerGeneratedFile(source, file);

        Assert.assertEquals(1, registry.findBindingsByToolCallId("call-2").size());
    }
}
