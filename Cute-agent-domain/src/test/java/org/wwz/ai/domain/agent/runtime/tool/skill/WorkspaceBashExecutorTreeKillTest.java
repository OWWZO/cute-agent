package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 超时必须杀整棵进程树：只杀直接子进程时，孙进程会继续持有工作区文件/端口并写出结果。
 */
public class WorkspaceBashExecutorTreeKillTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void timeoutKillsGrandchildrenToo() throws Exception {
        Assume.assumeTrue("仅验证 Windows 进程树行为",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path workspace = folder.newFolder("tree").toPath();
        Path marker = workspace.resolve("grandchild-marker.txt");
        String markerPath = marker.toAbsolutePath().toString();
        String command = "Start-Process powershell -WindowStyle Hidden -ArgumentList "
                + "'-NoProfile','-Command','Start-Sleep -Seconds 3; Set-Content -Path \""
                + markerPath + "\" -Value done'; Start-Sleep -Seconds 30";

        WorkspaceBashExecutor executor = new WorkspaceBashExecutor(SkillRuntimeOptions.builder().build());
        WorkspaceBashExecutor.ExecResult result = executor.exec(workspace, command, 800);

        Assert.assertTrue(result.isTimedOut());
        Thread.sleep(4_500);
        Assert.assertFalse("孙进程必须随超时一起被杀掉", Files.exists(marker));
    }
}
