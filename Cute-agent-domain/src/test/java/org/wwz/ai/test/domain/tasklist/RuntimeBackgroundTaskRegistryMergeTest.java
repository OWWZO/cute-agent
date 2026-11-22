package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 远端快照合并回归：DB 读取慢时本地已完成，陈旧远端行不能把 completed 降级并清空本地结果。
 */
public class RuntimeBackgroundTaskRegistryMergeTest {

    @Test
    public void staleRemoteSnapshotCannotDowngradeLocalCompletion() throws Exception {
        BlockingPort port = new BlockingPort();
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry("sess-merge", port);
        RuntimeBackgroundTask task = registry.registerLocalAgent("merge", "general-purpose", "p");
        registry.bindAgentId(task.getId(), "agent-merge");

        Thread waiter = new Thread(() -> registry.awaitUntilTerminal(task.getId()));
        waiter.start();
        Assert.assertTrue("等待线程必须已进入远端查询", port.entered.await(5, TimeUnit.SECONDS));

        // 远端行仍说 failed、agentId=null；本地在 DB 查询返回前完成。
        registry.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-merge")
                .content("本地结果")
                .build());
        port.release.countDown();
        waiter.join(5_000);

        RuntimeBackgroundTask merged = registry.get(task.getId()).orElseThrow();
        Assert.assertEquals("陈旧远端行不得把本地 completed 降级",
                RuntimeBackgroundTask.STATUS_COMPLETED, merged.getStatus());
        Assert.assertEquals("本地结果", merged.getOutput());
        Assert.assertEquals("agent-merge", merged.getAgentId());
    }

    private static final class BlockingPort implements TasklistPersistencePort {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public List<SessionTaskItem> loadTodos(String sessionId) {
            return List.of();
        }

        @Override
        public void replaceTodos(String sessionId, List<SessionTaskItem> items) {
        }

        @Override
        public List<RuntimeBackgroundTask> loadBackgroundTasks(String sessionId) {
            return List.of();
        }

        @Override
        public Optional<RuntimeBackgroundTask> findBackgroundTask(String sessionId, String taskId) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(RuntimeBackgroundTask.builder()
                    .id(taskId)
                    .status(RuntimeBackgroundTask.STATUS_FAILED)
                    .agentId(null)
                    .output(null)
                    .endedAtMs(123L)
                    .build());
        }

        @Override
        public void upsertBackgroundTask(String sessionId, RuntimeBackgroundTask task) {
        }
    }
}
