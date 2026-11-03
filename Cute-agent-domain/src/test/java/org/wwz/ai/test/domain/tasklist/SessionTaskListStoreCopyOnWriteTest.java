package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;

/**
 * 写时复制回归：update 不能原地修改读者已持有的条目引用。
 */
public class SessionTaskListStoreCopyOnWriteTest {

    @Test
    public void updatePublishesNewSnapshotInsteadOfMutatingLiveItem() {
        SessionTaskListStore store = new SessionTaskListStore("list");
        SessionTaskItem created = store.create("原标题", "原描述", null, null);
        SessionTaskItem readerSnapshot = store.get(created.getId()).orElseThrow();

        store.update(created.getId(), "新标题", null, SessionTaskItem.STATUS_COMPLETED,
                null, null, java.util.List.of(created.getId()), null);

        Assert.assertEquals("旧引用必须保持更新前内容", "原标题", readerSnapshot.getSubject());
        Assert.assertEquals(SessionTaskItem.STATUS_PENDING, readerSnapshot.getStatus());
        Assert.assertTrue(readerSnapshot.getBlocks().isEmpty());

        SessionTaskItem updated = store.get(created.getId()).orElseThrow();
        Assert.assertNotSame(readerSnapshot, updated);
        Assert.assertEquals("新标题", updated.getSubject());
        Assert.assertEquals(SessionTaskItem.STATUS_COMPLETED, updated.getStatus());
        Assert.assertEquals(java.util.List.of(created.getId()), updated.getBlocks());
    }
}
