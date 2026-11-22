package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;

import java.util.List;
import java.util.Map;

/**
 * replaceAll 先校验后落状态：被拒绝的写入不能清空现有列表。
 */
public class SessionTaskListStoreReplaceAllTest {

    @Test
    public void invalidStatusDoesNotWipeExistingTasks() {
        SessionTaskListStore store = new SessionTaskListStore("list-1");
        store.replaceAll(List.of(Map.of("subject", "任务 A", "status", "pending")));

        try {
            store.replaceAll(List.of(Map.of("content", "任务 B", "status", "blocked")));
            Assert.fail("非法 status 必须报错");
        } catch (IllegalArgumentException expected) {
            // 预期
        }

        List<SessionTaskItem> remaining = store.list();
        Assert.assertEquals("失败写入不能破坏已有列表", 1, remaining.size());
        Assert.assertEquals("任务 A", remaining.get(0).getSubject());
    }

    @Test
    public void validReplaceStillRebuildsList() {
        SessionTaskListStore store = new SessionTaskListStore("list-2");

        store.replaceAll(List.of(
                Map.of("content", "进行中", "status", "in_progress"),
                Map.of("content", "已完成", "status", "completed")));

        List<SessionTaskItem> items = store.list();
        Assert.assertEquals(2, items.size());
        Assert.assertEquals("1", items.get(0).getId());
        Assert.assertEquals(SessionTaskItem.STATUS_IN_PROGRESS, items.get(0).getStatus());
        Assert.assertEquals("2", items.get(1).getId());
        Assert.assertEquals(SessionTaskItem.STATUS_COMPLETED, items.get(1).getStatus());
    }

    @Test
    public void allCompletedItemsRemainUntilExplicitEmptyReplace() {
        SessionTaskListStore store = new SessionTaskListStore("list-3");

        store.replaceAll(List.of(
                Map.of("content", "任务 A", "status", "completed"),
                Map.of("content", "任务 B", "status", "completed")));

        Assert.assertEquals(2, store.list().size());
        Assert.assertEquals(SessionTaskItem.STATUS_COMPLETED, store.list().get(0).getStatus());

        store.replaceAll(List.of());
        Assert.assertTrue(store.list().isEmpty());
    }
}
