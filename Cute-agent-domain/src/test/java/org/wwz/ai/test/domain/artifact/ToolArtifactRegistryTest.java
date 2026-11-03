package org.wwz.ai.test.domain.artifact;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactBinding;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactRegistry;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;

/**
 * 重复登记同一文件必须返回注册表内的既有绑定对象。
 */
public class ToolArtifactRegistryTest {

    @Test
    public void duplicateRegistrationReturnsStoredBinding() {
        ToolArtifactRegistry registry = new ToolArtifactRegistry();
        ToolArtifactSource source = ToolArtifactSource.builder()
                .toolCallId("call-1")
                .toolName("workspace_write")
                .build();
        File file = File.builder()
                .fileName("a.txt")
                .ossUrl("https://example.com/a.txt")
                .build();

        ToolArtifactBinding first = registry.registerGeneratedFile(source, file);
        ToolArtifactBinding second = registry.registerGeneratedFile(source, file);

        Assert.assertSame(first, second);
        Assert.assertEquals(1, registry.listVisibleBindings().size());
        Assert.assertSame(first, registry.findBindingsByToolCallId("call-1").get(0));
    }
}
