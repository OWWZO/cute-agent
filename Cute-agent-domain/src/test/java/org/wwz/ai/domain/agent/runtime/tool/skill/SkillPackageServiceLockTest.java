package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 技能目录变更串行化：安装持有写锁时，并发的删除/写入必须等待，不能交叉删目录。
 */
public class SkillPackageServiceLockTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void concurrentDeleteWaitsForInstallLock() throws Exception {
        CountDownLatch findEntered = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        SkillRegistry registry = new BlockingRegistry(findEntered, releaseInstall);
        Path root = folder.newFolder("skills").toPath();
        SkillPackageService service = new SkillPackageService(
                SkillRuntimeOptions.builder().directories(List.of(root.toString())).build(), registry);

        Thread installer = new Thread(() -> service.installFromMarkdown(
                "locked-skill", "描述", "正文", false));
        installer.start();
        Assert.assertTrue(findEntered.await(5, TimeUnit.SECONDS));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> deleting = pool.submit(() -> service.deleteSkill("locked-skill"));
            Thread.sleep(200L);
            Assert.assertFalse("安装未结束时删除必须被锁挡住", deleting.isDone());

            releaseInstall.countDown();
            installer.join(5_000);
            Assert.assertTrue("安装完成后删除才能执行", deleting.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            releaseInstall.countDown();
        }
    }

    private static final class BlockingRegistry implements SkillRegistry {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingRegistry(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void refresh() {
        }

        @Override
        public String version() {
            return "v1";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Collection<SkillDefinition> listSkills() {
            return List.of();
        }

        @Override
        public Optional<SkillDefinition> findSkill(String skillName) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        @Override
        public SkillDefinition getRequiredSkill(String skillName) {
            throw new SkillLoadException("not found");
        }

        @Override
        public Path resolveAllowedPath(Path candidatePath) {
            return candidatePath;
        }

        @Override
        public String buildSkillDescription() {
            return "";
        }
    }
}
