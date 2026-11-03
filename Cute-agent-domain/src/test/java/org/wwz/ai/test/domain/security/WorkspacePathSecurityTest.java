package org.wwz.ai.test.domain.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.wwz.ai.domain.agent.ledger.replay.ArtifactRelativePath;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.tool.cli.MaterializedCliArtifact;

/**
 * 工作区相对路径安全回归：修复前 {@code ../../etc/passwd} / 盘符路径会原样进入 DTO 与回放载荷。
 */
public class WorkspacePathSecurityTest {

    @Test
    public void sanitizeRejectsTraversalAndAbsolutePaths() {
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("../../etc/passwd"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("..\\..\\windows\\system32"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("a/../../b"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("/etc/passwd"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("//server/share/x"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("C:/tmp/x"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("C:foo"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("~/secret"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize("file:///etc/passwd"));
    }

    @Test
    public void sanitizeKeepsNormalRelativePaths() {
        Assert.assertEquals("report/index.html", WorkspaceRelativePath.sanitize("report/index.html"));
        Assert.assertEquals("report/index.html", WorkspaceRelativePath.sanitize("./report/index.html"));
        Assert.assertEquals("report/index.html", WorkspaceRelativePath.sanitize("report\\index.html"));
        Assert.assertEquals("", WorkspaceRelativePath.sanitize(null));
    }

    @Test
    public void fileDtoDropsTraversalPathAtConstruction() {
        File file = File.builder()
                .fileName("passwd")
                .relativePath("../../etc/passwd")
                .build();
        Assert.assertEquals("", file.getRelativePath());

        File safe = File.builder().relativePath("./report/a.md").build();
        Assert.assertEquals("report/a.md", safe.getRelativePath());
    }

    @Test
    public void toolFileRefDropsTraversalPathFromBuilderAndSetter() {
        ToolFileRef built = ToolFileRef.builder().relativePath("../../etc/passwd").build();
        Assert.assertEquals("", built.getRelativePath());

        ToolFileRef assigned = new ToolFileRef();
        assigned.setRelativePath("..\\..\\x");
        Assert.assertEquals("", assigned.getRelativePath());
    }

    @Test
    public void materializedCliArtifactRejectsTraversalPath() {
        try {
            MaterializedCliArtifact.builder().relativePath("../../etc/passwd").build();
            Assert.fail("越界路径必须被构造期拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("workspace"));
        }
    }

    @Test
    public void artifactRelativePathFallsBackWhenMetadataEscapes() {
        ArtifactView artifact = ArtifactView.builder()
                .fileName("report.md")
                .metadataJson("{\"relativePath\":\"../../etc/passwd\"}")
                .build();
        Assert.assertEquals("report.md", ArtifactRelativePath.resolve(artifact));

        ArtifactView descriptionAttack = ArtifactView.builder()
                .fileName("report.md")
                .metadataJson("{\"description\":\"workspace:../../etc/passwd\"}")
                .build();
        Assert.assertEquals("report.md", ArtifactRelativePath.resolve(descriptionAttack));
    }

    @Test
    public void artifactRelativePathFallsBackWhenNormalizationIsEmpty() {
        // 修复前：元数据里是 /// 时 normalize 返回空串，不会回退到 fileName。
        ArtifactView artifact = ArtifactView.builder()
                .fileName("report.md")
                .metadataJson("{\"relativePath\":\"///\"}")
                .build();
        Assert.assertEquals("report.md", ArtifactRelativePath.resolve(artifact));
    }

    @Test
    public void artifactRelativePathKeepsSafeMetadata() {
        ArtifactView artifact = ArtifactView.builder()
                .fileName("fallback.md")
                .metadataJson("{\"relativePath\":\"./site/css/style.css\"}")
                .build();
        Assert.assertEquals("site/css/style.css", ArtifactRelativePath.resolve(artifact));
    }
}
