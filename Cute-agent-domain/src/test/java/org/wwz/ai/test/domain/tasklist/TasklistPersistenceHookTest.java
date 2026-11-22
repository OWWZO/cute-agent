package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存 fake 持久化端口：验证 store/registry 会调用 persist 钩子。
 */
public class TasklistPersistenceHookTest {

    @Test
    public void todoStorePersistsWholeListReplacement() {
        InMemoryPort port = new InMemoryPort();
        SessionTaskListStore store = new SessionTaskListStore("sess-1", port);
        store.replaceAll(List.of(
                Map.of("content", "写单测", "status", "in_progress"),
                Map.of("content", "跑测试", "status", "pending")
        ));
        Assert.assertEquals(2, port.todos.size());

        SessionTaskListStore reloaded = new SessionTaskListStore("sess-1", port);
        Assert.assertEquals(2, reloaded.list().size());
        Assert.assertEquals("写单测", reloaded.list().get(0).getSubject());
        Assert.assertEquals(SessionTaskItem.STATUS_IN_PROGRESS, reloaded.list().get(0).getStatus());
    }

    @Test
    public void completedTodosSurviveReloadUntilExplicitClear() {
        InMemoryPort port = new InMemoryPort();
        SessionTaskListStore store = new SessionTaskListStore("sess-completed", port);

        store.replaceAll(List.of(
                Map.of("content", "已完成 A", "status", "completed"),
                Map.of("content", "已完成 B", "status", "completed")));

        SessionTaskListStore reloaded = new SessionTaskListStore("sess-completed", port);
        Assert.assertEquals(2, reloaded.list().size());
        Assert.assertEquals(SessionTaskItem.STATUS_COMPLETED, reloaded.list().get(0).getStatus());

        reloaded.replaceAll(List.of());
        Assert.assertTrue(new SessionTaskListStore("sess-completed", port).list().isEmpty());
    }

    @Test
    public void todoStoreHydratesFromPort() {
        InMemoryPort port = new InMemoryPort();
        port.todos.put("sess-2:1", SessionTaskItem.builder()
                .id("1")
                .subject("已有任务")
                .description("desc")
                .status(SessionTaskItem.STATUS_PENDING)
                .build());
        SessionTaskListStore store = new SessionTaskListStore("sess-2", port);
        Assert.assertEquals(1, store.list().size());
        Assert.assertEquals("已有任务", store.list().get(0).getSubject());

        store.replaceAll(List.of(Map.of("content", "新任务", "status", "in_progress")));
        Assert.assertEquals(1, port.todos.size());
        Assert.assertTrue(port.todos.containsKey("sess-2:1"));
    }

    @Test
    public void backgroundRegistryPersistsAndHydrates() {
        InMemoryPort port = new InMemoryPort();
        RuntimeBackgroundTaskRegistry reg = new RuntimeBackgroundTaskRegistry("sess-bg", port);
        RuntimeBackgroundTask task = reg.registerLocalAgent("inspect", "general-purpose", "prompt");
        Assert.assertTrue(port.bgUpserts.get() >= 1);

        reg.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("a1")
                .content("done")
                .build());
        Assert.assertTrue(port.bgUpserts.get() >= 2);

        RuntimeBackgroundTaskRegistry reloaded = new RuntimeBackgroundTaskRegistry("sess-bg", port);
        RuntimeBackgroundTask loaded = reloaded.get(task.getId()).orElseThrow();
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_COMPLETED, loaded.getStatus());
        Assert.assertEquals("done", loaded.getOutput());
    }

    @Test
    public void hydratePreservesRunningForStartupReconciliation() {
        InMemoryPort port = new InMemoryPort();
        RuntimeBackgroundTask orphan = RuntimeBackgroundTask.builder()
                .id("orphan1")
                .type(RuntimeBackgroundTask.TYPE_LOCAL_AGENT)
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .description("left running")
                .startedAtMs(System.currentTimeMillis())
                .build();
        port.background.put("sess-x:orphan1", orphan);

        RuntimeBackgroundTaskRegistry reg = new RuntimeBackgroundTaskRegistry("sess-x", port);
        RuntimeBackgroundTask loaded = reg.get("orphan1").orElseThrow();
        // running 行可能属于仍在运行的 Future 或另一个 JVM；registry hydrate 不能误杀。
        // 重启后的 lost 收敛只由启动对账器统一完成。
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_RUNNING, loaded.getStatus());
        Assert.assertEquals(0, port.bgUpserts.get());
    }

    @Test
    public void markNotifiedPersistsConsumedFlag() {
        InMemoryPort port = new InMemoryPort();
        RuntimeBackgroundTaskRegistry reg = new RuntimeBackgroundTaskRegistry("sess-notified", port);
        RuntimeBackgroundTask task = reg.registerLocalAgent("inspect", "general-purpose", "prompt");
        reg.complete(task.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("agent-notified")
                .content("done")
                .build());
        int before = port.bgUpserts.get();

        reg.markNotified(List.of(task.getId()));

        Assert.assertTrue(Boolean.TRUE.equals(task.getNotified()));
        Assert.assertTrue(port.bgUpserts.get() > before);
    }

    private static final class InMemoryPort implements TasklistPersistencePort {
        final Map<String, SessionTaskItem> todos = new ConcurrentHashMap<>();
        final Map<String, RuntimeBackgroundTask> background = new ConcurrentHashMap<>();
        final AtomicInteger bgUpserts = new AtomicInteger();

        @Override
        public List<SessionTaskItem> loadTodos(String sessionId) {
            List<SessionTaskItem> list = new ArrayList<>();
            String prefix = sessionId + ":";
            for (Map.Entry<String, SessionTaskItem> e : todos.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    list.add(e.getValue());
                }
            }
            return list;
        }

        @Override
        public void replaceTodos(String sessionId, List<SessionTaskItem> items) {
            String prefix = sessionId + ":";
            todos.keySet().removeIf(k -> k.startsWith(prefix));
            if (items != null) {
                for (SessionTaskItem item : items) {
                    todos.put(prefix + item.getId(), item);
                }
            }
        }

        @Override
        public List<RuntimeBackgroundTask> loadBackgroundTasks(String sessionId) {
            List<RuntimeBackgroundTask> list = new ArrayList<>();
            String prefix = sessionId + ":";
            for (Map.Entry<String, RuntimeBackgroundTask> e : background.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    list.add(e.getValue());
                }
            }
            return list;
        }

        @Override
        public Optional<RuntimeBackgroundTask> findBackgroundTask(String sessionId, String taskId) {
            return Optional.ofNullable(background.get(sessionId + ":" + taskId));
        }

        @Override
        public void upsertBackgroundTask(String sessionId, RuntimeBackgroundTask task) {
            bgUpserts.incrementAndGet();
            background.put(sessionId + ":" + task.getId(), task);
        }
    }
}
