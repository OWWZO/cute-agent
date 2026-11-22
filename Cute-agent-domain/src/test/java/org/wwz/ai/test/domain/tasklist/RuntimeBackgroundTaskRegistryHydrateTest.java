package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * hydrate 只恢复持久化快照，不能把可能仍有 Future 的 running 行误判成 lost。
 */
public class RuntimeBackgroundTaskRegistryHydrateTest {

    @Test
    public void runningRowRemainsRunningUntilReconcilerProvesItOrphaned() {
        TasklistPersistencePort persistence = mock(TasklistPersistencePort.class);
        RuntimeBackgroundTask task = RuntimeBackgroundTask.builder()
                .id("task-1")
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .description("后台任务")
                .build();
        when(persistence.loadBackgroundTasks("s1")).thenReturn(List.of(task));

        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry("s1", persistence);

        RuntimeBackgroundTask hydrated = registry.get("task-1").orElseThrow();
        Assert.assertEquals("hydrate 不能误杀同进程仍在运行的任务",
                RuntimeBackgroundTask.STATUS_RUNNING, hydrated.getStatus());
        Assert.assertNull(hydrated.getEndedAtMs());
        Assert.assertNotNull("内存视图仍需可取消令牌", hydrated.getCancellation());
        verify(persistence, never()).upsertBackgroundTask(any(), any());
    }
}
