package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.Locale;

/**
 * 本地 bash 输出必须按 UTF-8 解码（Windows PowerShell 默认 OEM 代码页），截断不能切坏代理对。
 */
public class WorkspaceBashExecutorOutputTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void windowsArgvForcesUtf8ConsoleOutput() {
        List<String> argv = WorkspaceBashExecutor.buildShellArgv("echo hi", "Windows 11");

        Assert.assertEquals("powershell.exe", argv.get(0));
        String command = argv.get(argv.size() - 1);
        Assert.assertTrue(command.contains("OutputEncoding"));
        Assert.assertTrue(command.endsWith("echo hi"));
    }

    @Test
    public void linuxArgvIsUnchanged() {
        Assert.assertEquals(List.of("bash", "-lc", "echo hi"),
                WorkspaceBashExecutor.buildShellArgv("echo hi", "Linux"));
    }

    @Test
    public void truncationBacksOffFromHighSurrogateAtHeadBoundary() {
        String raw = "a".repeat(49) + "😀" + "b".repeat(149);

        String truncated = WorkspaceBashExecutor.truncateMiddle(raw, 100);

        Assert.assertTrue(truncated.startsWith("a".repeat(49) + "\n"));
        Assert.assertTrue(truncated.endsWith("b"));
        assertNoLoneSurrogates(truncated);
    }

    @Test
    public void truncationBacksOffFromLowSurrogateAtTailBoundary() {
        String raw = "a".repeat(159) + "😀" + "b".repeat(38);

        String truncated = WorkspaceBashExecutor.truncateMiddle(raw, 100);

        Assert.assertTrue(truncated.startsWith("a".repeat(50) + "\n"));
        Assert.assertTrue("尾部从低代理项之后开始", truncated.endsWith("b".repeat(38)));
        assertNoLoneSurrogates(truncated);
    }

    @Test
    public void nonAsciiStdoutRoundTripsAsUtf8() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String command = windows ? "Write-Output '技能-ok'" : "printf '技能-ok'";
        WorkspaceBashExecutor executor = new WorkspaceBashExecutor(SkillRuntimeOptions.builder().build());

        WorkspaceBashExecutor.ExecResult result = executor.exec(
                folder.newFolder("ws-utf8").toPath(), command, 10_000);

        Assert.assertEquals(Integer.valueOf(0), result.getExitCode());
        Assert.assertTrue("stdout=" + result.getStdout(), result.getStdout().contains("技能-ok"));
    }

    private static void assertNoLoneSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                Assert.assertTrue("高代理项后必须是低代理项，index=" + i,
                        i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
                i++;
            } else {
                Assert.assertFalse("出现孤立低代理项，index=" + i, Character.isLowSurrogate(c));
            }
        }
    }
}
