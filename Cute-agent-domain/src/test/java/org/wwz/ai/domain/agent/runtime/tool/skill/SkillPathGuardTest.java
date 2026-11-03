package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class SkillPathGuardTest {

    private final SkillPathGuard guard = new SkillPathGuard();

    @Test
    public void shouldRejectTraversalAndAbsolutePaths() {
        Assert.assertTrue(guard.hasTraversal("../etc/passwd"));
        Assert.assertTrue(guard.hasTraversal("a/../../b"));
        Assert.assertTrue(guard.hasTraversal("/etc/passwd"));
        Assert.assertTrue(guard.hasTraversal("~/secret"));
        Assert.assertTrue(guard.hasTraversal("C:/windows/system32"));
        Assert.assertTrue(guard.hasTraversal("C:windows"));
        Assert.assertTrue(guard.hasTraversal("//server/share"));
        Assert.assertTrue(guard.hasTraversal("\\\\server\\share"));
    }

    @Test
    public void shouldRejectBlankOrNull() {
        Assert.assertTrue(guard.hasTraversal(null));
        Assert.assertTrue(guard.hasTraversal("   "));
    }

    @Test
    public void shouldAcceptNormalRelativePaths() {
        Assert.assertFalse(guard.hasTraversal("references/metrics.md"));
        Assert.assertFalse(guard.hasTraversal("scripts/run.py"));
        Assert.assertFalse(guard.hasTraversal("SKILL.md"));
    }

    @Test
    public void shouldReturnCandidateWhenInsideRoot() throws IOException {
        Path root = Files.createTempDirectory("guard-root-");
        Path candidate = root.resolve("references/metrics.md");

        Assert.assertEquals(candidate.toAbsolutePath().normalize(), guard.ensureUnderRoot(root, candidate));
    }

    @Test
    public void shouldThrowWhenCandidateOutsideRoot() throws IOException {
        Path root = Files.createTempDirectory("guard-root-");
        Path outside = Files.createTempDirectory("guard-outside-").resolve("x.md");

        Assert.assertThrows(SkillLoadException.class, () -> guard.ensureUnderRoot(root, outside));
    }

    @Test
    public void shouldThrowWhenLexicalCheckFails() throws IOException {
        Path root = Files.createTempDirectory("guard-root-");

        Assert.assertThrows(SkillLoadException.class,
                () -> guard.ensureUnderRoot(root, root.resolve("../../etc/passwd")));
    }

    @Test
    public void shouldAllowMissingCandidateWhenParentIsInsideRoot() throws IOException {
        Path root = Files.createTempDirectory("guard-root-");
        Path candidate = root.resolve("references/missing.md");

        // 目标不存在但父目录链路在 root 内：仍应放行，由后续存在性检查给出业务错误。
        Assert.assertEquals(candidate.toAbsolutePath().normalize(), guard.ensureUnderRoot(root, candidate));
    }

    @Test
    public void shouldRejectMissingCandidateUnderSymlinkedParent() throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("guard-root-");
        Path outside = Files.createTempDirectory("guard-outside-");
        Path link = root.resolve("link");
        linkDirectory(link, outside);

        Path candidate = link.resolve("missing.md");
        Assert.assertFalse(Files.exists(candidate));
        Assert.assertThrows(SkillLoadException.class,
                () -> guard.ensureUnderRoot(root, candidate));
    }

    /** 软链接优先；Windows 普通权限下退化为目录联接（junction），两者都能模拟父目录逃逸。 */
    private static void linkDirectory(Path link, Path target) throws IOException, InterruptedException {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                Assume.assumeNoException("当前文件系统不允许创建符号链接", e);
            }
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        Assume.assumeTrue("无法创建目录联接", process.waitFor() == 0);
    }
}
