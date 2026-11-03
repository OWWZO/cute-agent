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
 * hydrate 只修内存视图：不能把可能属于其它 JVM 的 running 行改写成 failed。
 */
public class RuntimeBackgroundTaskRegistryHydrateTest {

    @Test
    public void runningRowBecomesLostInMemoryWithoutPersisting() {
        TasklistPersistencePort persistence = mock(TasklistPersistencePort.class);
        RuntimeBackgroundTask task = RuntimeBackgroundTask.builder()
                .id("task-1")
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .description("后台任务")
                .build();
        when(persistence.loadBackgroundTasks("s1")).thenReturn(List.of(task));

        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry("s1", persistence);

        RuntimeBackgroundTask hydrated = registry.get("task-1").orElseThrow();
        Assert.assertEquals("重启孤儿应标 lost 而不是 failed",
                RuntimeBackgroundTask.STATUS_LOST, hydrated.getStatus());
        Assert.assertNotNull(hydrated.getEndedAtMs());
        Assert.assertNotNull("内存视图仍需可取消令牌", hydrated.getCancellation());
        verify(persistence, never()).upsertBackgroundTask(any(), any());
    }
}
