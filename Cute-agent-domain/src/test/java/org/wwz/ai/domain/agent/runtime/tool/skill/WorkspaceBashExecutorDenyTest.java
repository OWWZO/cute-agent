package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地 bash 不是沙箱：至少要有高危命令拒绝与审计日志。
 */
public class WorkspaceBashExecutorDenyTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void dangerousCommandsAreDeniedBeforeSpawn() throws Exception {
        Path workspace = folder.newFolder("ws").toPath();
        WorkspaceBashExecutor executor = new WorkspaceBashExecutor(SkillRuntimeOptions.builder().build());

        WorkspaceBashExecutor.ExecResult result = executor.exec(workspace, "rm -rf /", 1_000);

        Assert.assertEquals(Integer.valueOf(126), result.getExitCode());
        Assert.assertTrue(result.getStderr().contains("command denied by policy"));
    }

    @Test
    public void ordinaryCommandsPassDenyList() {
        Assert.assertNull(WorkspaceBashExecutor.deniedReason("echo hello", null));
        Assert.assertNotNull(WorkspaceBashExecutor.deniedReason("RM -RF /", null));
        Assert.assertNotNull(WorkspaceBashExecutor.deniedReason("mkfs.ext4 /dev/sda", null));
    }

    @Test
    public void extraPatternsAreConfigurable() {
        Assert.assertNotNull(WorkspaceBashExecutor.deniedReason("curl secret", List.of("(?i)curl\\s+secret")));
    }
}
